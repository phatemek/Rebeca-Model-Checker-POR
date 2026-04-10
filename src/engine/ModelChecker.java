package engine;

import model.GlobalSnapshot;
import por.SafetyAnalyzer;
import rebec.RebecInstance;

import java.util.ArrayList;
import java.util.List;

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
 */
public class ModelChecker {

    private final RebecInstance[] instances;
    private final boolean         usePOR;
    private final StateStorage    storage    = new StateStorage();
    private final List<String>    violations = new ArrayList<>();

    public ModelChecker(RebecInstance[] instances, boolean usePOR) {
        this.instances = instances;
        this.usePOR    = usePOR;
        if (usePOR) SafetyAnalyzer.analyze(instances);
    }

    /**
     * Runs the DFS from the current (initial) state and returns all violations found.
     */
    public List<String> check() {
        GlobalSnapshot initial = new GlobalSnapshot(instances);
        dfs(initial);
        System.out.printf("States explored: %d%n", storage.visitedCount());
        if (violations.isEmpty()) System.out.println("No violations found.");
        return violations;
    }

    // -------------------------------------------------------------------------

    private void dfs(GlobalSnapshot current) {
        if (storage.isVisited(current)) return;

        storage.add(current);
        storage.pushStack(current);
        current.restore(instances);

        List<RebecInstance> enabled = enabledRebecs();

        if (enabled.isEmpty()) {
            report("DEADLOCK detected");
        } else {
            List<RebecInstance> ample = usePOR ? computeAmpleSet(enabled, current) : enabled;

            for (RebecInstance r : ample) {
                current.restore(instances);
                r.execute(instances);
                checkOverflows(r);
                dfs(new GlobalSnapshot(instances));
            }
        }

        storage.popStack(current);
    }

    // -------------------------------------------------------------------------
    // Ample set selection (POR)
    // -------------------------------------------------------------------------

    /**
     * Tries to find a singleton ample set.
     *
     * A singleton {r} is valid when:
     *   1. r's current action is safe (statically determined by SafetyAnalyzer).
     *   2. Executing r does not lead to a state already on the DFS stack
     *      (stack proviso — checked by peeking at the resulting snapshot).
     *
     * If no candidate satisfies both conditions, returns all enabled rebecs
     * so the state is fully expanded.
     */
    private List<RebecInstance> computeAmpleSet(List<RebecInstance> enabled,
                                                 GlobalSnapshot current) {
        for (RebecInstance r : enabled) {
            if (!r.isCurrentActionSafe()) continue;

            // Peek: temporarily execute r, capture the next snapshot, then restore.
            // restore() resets hasOverflowed on every rebec, so no extra cleanup needed.
            current.restore(instances);
            r.execute(instances);
            GlobalSnapshot next = new GlobalSnapshot(instances);
            current.restore(instances);

            if (!storage.isOnStack(next))
                return List.of(r);
        }
        return enabled;
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
        violations.add(msg);
        System.out.println("Violation: " + msg);
    }
}
