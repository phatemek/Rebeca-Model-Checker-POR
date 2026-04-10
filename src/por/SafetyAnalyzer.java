package por;

import org.rebecalang.compiler.modelcompiler.corerebeca.objectmodel.*;
import rebec.RebecInstance;

import java.util.*;

/**
 * Static safety analysis — run once before the DFS begins.
 *
 * An action is safe iff it is invisible AND globally independent (Definition 19).
 * Theorem 3 reduces this to a per-statement check:
 *
 *   1. Invisibility   — trivially satisfied for all actions when checking deadlock,
 *                       queue overflow, and assertion violations (no LTL propositions).
 *
 *   2. Safe assignment — all assignments in Rebeca modify only the executing rebec's
 *                       local state variables.  They cannot enable or disable any other
 *                       rebec's action, so they are always safe.  No analysis needed.
 *
 *   3. Safe send       — a send to target T is safe iff the current rebec is the
 *                       exclusive sender to T (Lemma 7).  We check this by building
 *                       a map of every instance that ever sends to each target.
 *
 *   4. initial msgsrv  — always safe regardless of content.  The paper exempts it
 *                       because it is placed in every queue at the initial state and
 *                       cannot disable or be disabled by any other action.
 *
 * After analyze() returns, each RebecInstance has its safe msgsrvs marked so the
 * DFS engine can query isCurrentActionSafe() at runtime.
 */
public class SafetyAnalyzer {

    public static void analyze(RebecInstance[] instances) {
        // Build: targetId → set of all instance IDs that ever send to it
        Map<Integer, Set<Integer>> senders = buildSendersMap(instances);

        for (RebecInstance r : instances) {
            for (Map.Entry<String, MsgsrvDeclaration> entry : r.getMsgsrvs().entrySet()) {
                String msgsrvName = entry.getKey();

                // initial is always safe — exempt from analysis
                if ("initial".equals(msgsrvName)) {
                    r.markSafe(msgsrvName);
                    continue;
                }

                // All assignments are safe; only sends need checking
                if (allSendsAreSafe(r, entry.getValue(), senders))
                    r.markSafe(msgsrvName);
            }
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Builds the map: targetInstanceId → set of sender instance IDs.
     * We over-approximate by collecting every statically reachable send
     * across all branches, regardless of runtime conditions.
     */
    private static Map<Integer, Set<Integer>> buildSendersMap(RebecInstance[] instances) {
        Map<Integer, Set<Integer>> senders = new HashMap<>();
        for (RebecInstance r : instances) {
            for (MsgsrvDeclaration msgsrv : r.getMsgsrvs().values()) {
                for (String targetName : collectSendTargetNames(msgsrv.getBlock())) {
                    int targetId = resolveTarget(r, targetName);
                    if (targetId >= 0)
                        senders.computeIfAbsent(targetId, k -> new HashSet<>()).add(r.id);
                }
            }
        }
        return senders;
    }

    /**
     * Returns true iff every send in this msgsrv goes to a target where
     * the given rebec is the exclusive sender.
     */
    private static boolean allSendsAreSafe(RebecInstance r,
                                            MsgsrvDeclaration msgsrv,
                                            Map<Integer, Set<Integer>> senders) {
        for (String targetName : collectSendTargetNames(msgsrv.getBlock())) {
            int targetId = resolveTarget(r, targetName);
            if (targetId < 0) continue;
            Set<Integer> s = senders.getOrDefault(targetId, Collections.emptySet());
            if (s.size() > 1) return false;  // another rebec also sends to this target
        }
        return true;
    }

    /**
     * Resolves a send-target name to a concrete instance ID.
     * "self" resolves to the rebec's own ID; a known-rebec name resolves
     * via the bound knownRebecs[] array.  Returns -1 if unresolvable.
     */
    private static int resolveTarget(RebecInstance r, String name) {
        if ("self".equals(name)) return r.id;
        List<String> knownNames = r.getKnownRebecNames();
        int idx = knownNames.indexOf(name);
        return idx >= 0 ? r.knownRebecs[idx] : -1;
    }

    /**
     * Collects the receiver names of all send statements reachable in a block,
     * including inside both branches of every conditional.
     */
    private static Set<String> collectSendTargetNames(BlockStatement block) {
        Set<String> targets = new HashSet<>();
        collectFromStatement(block, targets);
        return targets;
    }

    private static void collectFromStatement(Statement st, Set<String> targets) {
        if (st instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) st).getStatements())
                collectFromStatement(s, targets);

        } else if (st instanceof ConditionalStatement) {
            ConditionalStatement cs = (ConditionalStatement) st;
            collectFromStatement(cs.getStatement(), targets);
            if (cs.getElseStatement() != null)
                collectFromStatement(cs.getElseStatement(), targets);

        } else if (st instanceof DotPrimary) {
            // send statement: left side is the receiver
            Expression left = ((DotPrimary) st).getLeft();
            if (left instanceof TermPrimary)
                targets.add(((TermPrimary) left).getName());
        }
        // BinaryExpression (assignments) — always safe, not collected
    }
}
