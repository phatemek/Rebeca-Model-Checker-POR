package model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of one rebec's local state: its queue and state variables.
 * Used as the unit of comparison and hashing when the DFS engine stores global states.
 */
public final class RebecSnapshot {

    public final List<Message> queue;  // ordered, immutable copy of the queue
    public final Object[]      vars;   // state variable values in declaration order

    public RebecSnapshot(List<Message> queue, Object[] vars) {
        this.queue = List.copyOf(queue);
        this.vars  = vars.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RebecSnapshot)) return false;
        RebecSnapshot s = (RebecSnapshot) o;
        if (!Arrays.deepEquals(vars, s.vars)) return false;
        if (queue.size() != s.queue.size()) return false;
        // Multiset (bag) comparison — order of messages in queue does not matter
        Map<Message, Integer> counts = new HashMap<>();
        for (Message m : queue) counts.merge(m, 1, Integer::sum);
        for (Message m : s.queue) {
            int c = counts.getOrDefault(m, 0);
            if (c == 0) return false;
            if (c == 1) counts.remove(m);
            else counts.put(m, c - 1);
        }
        return true;
    }

    @Override
    public int hashCode() {
        // Commutative sum so that permutations of the same messages hash identically
        int queueHash = 0;
        for (Message m : queue) queueHash += m.hashCode();
        return 31 * queueHash + Arrays.deepHashCode(vars);
    }
}
