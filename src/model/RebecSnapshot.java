package model;

import java.util.Arrays;
import java.util.List;

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
        return queue.equals(s.queue) && Arrays.deepEquals(vars, s.vars);
    }

    @Override
    public int hashCode() {
        return 31 * queue.hashCode() + Arrays.deepHashCode(vars);
    }
}
