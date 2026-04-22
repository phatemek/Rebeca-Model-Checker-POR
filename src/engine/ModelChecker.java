package engine;

import model.GlobalSnapshot;
import por.SafetyAnalyzer;
import rebec.RebecInstance;
import rebec.RebecInstance.NeedMoreChoicesException;
import rebec.StepResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DFS-based model checker.
 *
 * Checks for: deadlock, queue overflow, assertion violations.
 * Optionally applies static partial order reduction (POR).
 *
 * POR strategy (Section 4.1 of the paper):
 *   At each state, try to find a single enabled rebec whose current action is safe.
 *   If its execution does not lead back to a state on the DFS stack (stack proviso),
 *   use it as the sole ample set — i.e., only explore that one rebec from this state.
 *   Otherwise fall back to all enabled rebecs (fully expand the state).
 *
 * Non-determinism (?(expr1, expr2, ...)):
 *   executeAndDfs() recursively tries all possible choice schedules for a rebec's
 *   msgsrv before recursing into the DFS, so every non-det outcome is explored.
 */
public class ModelChecker {

    private final RebecInstance[] instances;
    private final boolean         usePOR;
    private final boolean         detectDeadlock;
    private final StateStorage    storage      = new StateStorage();
    private final List<String>    violations   = new ArrayList<>();
    private final Set<String>     seenViolations = new LinkedHashSet<>();
    private       PrintWriter     stateLog     = null;
    private       File            tempFile     = null;
    private       PrintWriter     statePrinter = null;
    private       int             stateCount   = 0;

    public ModelChecker(RebecInstance[] instances, boolean usePOR, boolean detectDeadlock) {
        this.instances      = instances;
        this.usePOR         = usePOR;
        this.detectDeadlock = detectDeadlock;
        if (usePOR) SafetyAnalyzer.analyze(instances);
    }

    public List<String> check() {
        GlobalSnapshot initial = new GlobalSnapshot(instances);
        dfs(initial);
        System.out.printf("States explored: %d%n", storage.visitedCount());
        if (violations.isEmpty()) System.out.println("No violations found.");
        if (stateLog != null) {
            stateLog.println("=== Model Checker Results ===");
            if (violations.isEmpty()) {
                stateLog.println("Result: No violations found");
            } else {
                for (String v : violations)
                    stateLog.println("Result: " + v);
            }
            stateLog.printf("States explored: %d%n", storage.visitedCount());
            stateLog.println();
            statePrinter.flush();
            statePrinter.close();
            appendTempFile();
            stateLog.flush();
        }
        return violations;
    }

    public int getStatesExplored() { return storage.visitedCount(); }

    public void setStateLog(PrintWriter w) {
        this.stateLog = w;
        try {
            this.tempFile    = File.createTempFile("mc-states-", ".tmp");
            this.statePrinter = new PrintWriter(new FileWriter(tempFile));
        } catch (IOException e) {
            throw new RuntimeException("Cannot create temp file for state log", e);
        }
    }

    // -------------------------------------------------------------------------

    private void dfs(GlobalSnapshot current) {
        if (storage.isVisited(current)) return;

        storage.add(current);
        if (statePrinter != null) {
            statePrinter.printf("--- State #%d ---%n", ++stateCount);
            for (int i = 0; i < instances.length; i++)
                statePrinter.println("  " + instances[i].describeSnapshot(current.snapshots[i]));
            statePrinter.println();
            statePrinter.flush();
        }
        storage.pushStack(current);
        current.restore(instances);

        List<RebecInstance> enabled = enabledRebecs();

        if (enabled.isEmpty()) {
            if (detectDeadlock) report("DEADLOCK detected");
        } else {
            List<RebecInstance> ample = usePOR ? computeAmpleSet(enabled, current) : enabled;

            for (RebecInstance r : ample) {
                String action = r.enabledAction();
                executeAndDfs(r, current, action, Collections.emptyList());
            }
        }

        storage.popStack(current);
    }

    // -------------------------------------------------------------------------
    // Ample set selection (POR)
    // -------------------------------------------------------------------------

    private List<RebecInstance> computeAmpleSet(List<RebecInstance> enabled,
                                                 GlobalSnapshot current) {
        for (RebecInstance r : enabled) {
            if (!r.isCurrentActionSafe()) continue;
            if (!anyPeekOnStack(r, current))
                return List.of(r);
        }
        return enabled;
    }

    // -------------------------------------------------------------------------
    // Non-determinism helpers
    // -------------------------------------------------------------------------

    /**
     * Executes rebec r with the given choice schedule and recurses into DFS.
     * If more choices are needed, recursively tries all alternatives.
     */
    private void executeAndDfs(RebecInstance r, GlobalSnapshot current,
                                String action, List<Integer> choices) {
        current.restore(instances);
        r.setChoiceSchedule(choices);
        try {
            StepResult result = r.execute(instances);
            if (result == StepResult.ASSERTION_FAILED) {
                report("ASSERTION_FAILED: " + r.name + " failed an assertion in " + action + "()");
                return;
            }
            checkOverflows(r);
            dfs(new GlobalSnapshot(instances));
        } catch (NeedMoreChoicesException e) {
            for (int i = 0; i < e.numChoices; i++) {
                List<Integer> extended = new ArrayList<>(choices);
                extended.add(i);
                executeAndDfs(r, current, action, extended);
            }
        }
    }

    /** Returns true if any non-det outcome of executing r leads to a state on the DFS stack. */
    private boolean anyPeekOnStack(RebecInstance r, GlobalSnapshot current) {
        return peekOnStack(r, current, Collections.emptyList());
    }

    private boolean peekOnStack(RebecInstance r, GlobalSnapshot current, List<Integer> choices) {
        current.restore(instances);
        r.setChoiceSchedule(choices);
        try {
            r.execute(instances);
            GlobalSnapshot next = new GlobalSnapshot(instances);
            current.restore(instances);
            return storage.isOnStack(next);
        } catch (NeedMoreChoicesException e) {
            current.restore(instances);
            for (int i = 0; i < e.numChoices; i++) {
                List<Integer> extended = new ArrayList<>(choices);
                extended.add(i);
                if (peekOnStack(r, current, extended)) return true;
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<RebecInstance> enabledRebecs() {
        List<RebecInstance> enabled = new ArrayList<>();
        for (RebecInstance r : instances)
            if (r.isEnabled()) enabled.add(r);
        return enabled;
    }

    private void checkOverflows(RebecInstance executor) {
        for (RebecInstance r : instances) {
            if (r.hasOverflowed()) {
                report("QUEUE_OVERFLOW: " + executor.name
                        + " caused overflow in " + r.name + "'s queue");
                r.resetOverflow();
            }
        }
    }

    private void report(String msg) {
        if (!seenViolations.add(msg)) return;  // deduplicate: already reported this violation
        violations.add(msg);
        System.out.println("Violation: " + msg);
        if (statePrinter != null) {
            statePrinter.println("*** VIOLATION: " + msg + " ***");
            statePrinter.println("--- Violating state ---");
            for (RebecInstance r : instances)
                statePrinter.println("  " + r.describeSnapshot(r.takeSnapshot()));
            statePrinter.println();
            statePrinter.flush();
        }
    }

    private void appendTempFile() {
        try (BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
            String line;
            while ((line = reader.readLine()) != null)
                stateLog.println(line);
        } catch (IOException e) {
            stateLog.println("(error reading state log: " + e.getMessage() + ")");
        } finally {
            tempFile.delete();
        }
    }
}
