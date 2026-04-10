package model;

import rebec.RebecInstance;

import java.util.Arrays;

/**
 * An immutable snapshot of the entire system: one RebecSnapshot per rebec,
 * indexed by rebec ID.  Used as the key in StateStorage's hash map.
 */
public final class GlobalSnapshot {

    public final RebecSnapshot[] snapshots;

    /** Captures the current state of every rebec instance. */
    public GlobalSnapshot(RebecInstance[] instances) {
        snapshots = new RebecSnapshot[instances.length];
        for (int i = 0; i < instances.length; i++)
            snapshots[i] = instances[i].takeSnapshot();
    }

    /** Restores every rebec instance to the state captured in this snapshot. */
    public void restore(RebecInstance[] instances) {
        for (int i = 0; i < instances.length; i++)
            instances[i].restore(snapshots[i]);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GlobalSnapshot)) return false;
        return Arrays.equals(snapshots, ((GlobalSnapshot) o).snapshots);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(snapshots);
    }
}
