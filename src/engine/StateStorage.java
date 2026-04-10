package engine;

import model.GlobalSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores visited global states and tracks which ones are currently on the DFS stack.
 * The on-stack flag is used by the POR stack proviso check.
 */
public class StateStorage {

    // true  = visited AND currently on the DFS stack
    // false = visited AND already popped from the stack
    private final Map<GlobalSnapshot, Boolean> states = new HashMap<>();

    public boolean isVisited(GlobalSnapshot s)  { return states.containsKey(s); }
    public void    add(GlobalSnapshot s)         { states.put(s, false); }
    public void    pushStack(GlobalSnapshot s)   { states.put(s, true); }
    public void    popStack(GlobalSnapshot s)    { states.put(s, false); }
    public boolean isOnStack(GlobalSnapshot s)   { return Boolean.TRUE.equals(states.get(s)); }
    public int     visitedCount()                { return states.size(); }
}
