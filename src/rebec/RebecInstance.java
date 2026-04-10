package rebec;

import model.Message;
import model.RebecSnapshot;
import org.rebecalang.compiler.modelcompiler.corerebeca.objectmodel.*;

import java.util.*;

/**
 * A generic, interpreter-based rebec instance.
 * Holds the AST of each message server and executes them dynamically,
 * so no per-reactive-class Java subclass is needed.
 */
public class RebecInstance {

    public final int    id;
    public final String name;
    public final String className;

    /** IDs of known rebecs in declaration order — set by the translator after all instances are created. */
    public int[] knownRebecs;

    private final List<String>                   knownRebecNames; // matches knownRebecs[] index-for-index
    private final List<String>                   stateVarNames;   // for ordered snapshot / restore
    private final Map<String, Object>            vars;            // current state variable values
    private final Map<String, MsgsrvDeclaration> msgsrvs;         // AST per message name

    private final int           maxQueueSize;
    private       Deque<Message> queue;
    private       boolean        hasOverflowed;
    private final Set<String>    safeMsgsrvs = new HashSet<>();

    public RebecInstance(int id,
                         String name,
                         String className,
                         int maxQueueSize,
                         List<String> knownRebecNames,
                         List<String> stateVarNames,
                         Map<String, Object> initialVars,
                         Map<String, MsgsrvDeclaration> msgsrvs) {
        this.id              = id;
        this.name            = name;
        this.className       = className;
        this.maxQueueSize    = maxQueueSize;
        this.knownRebecNames = knownRebecNames;
        this.stateVarNames   = stateVarNames;
        this.vars            = new LinkedHashMap<>(initialVars);
        this.msgsrvs         = msgsrvs;
        this.queue           = new ArrayDeque<>();
        this.hasOverflowed   = false;
    }

    // -------------------------------------------------------------------------
    // Queue interface
    // -------------------------------------------------------------------------

    public boolean isEnabled()     { return !queue.isEmpty(); }
    public String  enabledAction() { Message h = queue.peek(); return h == null ? null : h.name; }

    public void enqueue(Message message) {
        if (queue.size() >= maxQueueSize) hasOverflowed = true;
        else queue.addLast(message);
    }

    public boolean hasOverflowed() { return hasOverflowed; }
    public void    resetOverflow() { hasOverflowed = false; }

    // -------------------------------------------------------------------------
    // Safety marking — used by SafetyAnalyzer (POR)
    // -------------------------------------------------------------------------

    public void    markSafe(String msgsrvName)       { safeMsgsrvs.add(msgsrvName); }
    public boolean isCurrentActionSafe()             { String a = enabledAction(); return a != null && safeMsgsrvs.contains(a); }

    /** Exposes the msgsrv map for static analysis. */
    public Map<String, MsgsrvDeclaration> getMsgsrvs() { return Collections.unmodifiableMap(msgsrvs); }

    /** Exposes the known-rebec name list (parallel to knownRebecs[]) for static analysis. */
    public List<String> getKnownRebecNames() { return Collections.unmodifiableList(knownRebecNames); }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Pops the head message and runs the corresponding message server.
     * Returns DISABLED if the queue is empty; OK otherwise.
     * Queue overflow from sends during execution is tracked via hasOverflowed().
     */
    public StepResult execute(RebecInstance[] allRebecs) {
        if (!isEnabled()) return StepResult.DISABLED;
        Message msg = queue.pollFirst();
        MsgsrvDeclaration msgsrv = msgsrvs.get(msg.name);
        if (msgsrv == null) return StepResult.OK;   // unknown message — skip silently
        executeBlock(msgsrv.getBlock(), allRebecs, msg.senderId);
        return StepResult.OK;
    }

    // -------------------------------------------------------------------------
    // Snapshot / restore — used by the DFS engine for backtracking
    // -------------------------------------------------------------------------

    public RebecSnapshot takeSnapshot() {
        return new RebecSnapshot(new ArrayList<>(queue), snapshotVars());
    }

    public void restore(RebecSnapshot snapshot) {
        queue         = new ArrayDeque<>(snapshot.queue);
        hasOverflowed = false;
        restoreVars(snapshot.vars);
    }

    // -------------------------------------------------------------------------
    // AST Interpreter
    // -------------------------------------------------------------------------

    private void executeBlock(BlockStatement block, RebecInstance[] allRebecs, int senderId) {
        for (Statement st : block.getStatements())
            executeStatement(st, allRebecs, senderId);
    }

    private void executeStatement(Statement st, RebecInstance[] allRebecs, int senderId) {
        if (st instanceof BlockStatement) {
            executeBlock((BlockStatement) st, allRebecs, senderId);

        } else if (st instanceof ConditionalStatement) {
            ConditionalStatement cs = (ConditionalStatement) st;
            boolean cond = toBoolean(evalExpr(cs.getCondition(), allRebecs, senderId));
            if (cond)
                executeStatement(cs.getStatement(), allRebecs, senderId);
            else if (cs.getElseStatement() != null)
                executeStatement(cs.getElseStatement(), allRebecs, senderId);

        } else if (st instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) st;
            if ("=".equals(bin.getOperator())) {
                String varName = ((TermPrimary) bin.getLeft()).getName();
                Object value   = evalExpr(bin.getRight(), allRebecs, senderId);
                vars.put(varName, value);
            }

        } else if (st instanceof DotPrimary) {
            executeSend((DotPrimary) st, allRebecs, senderId);
        }
        // Other statement types (assertions, etc.) can be added here as needed
    }

    private Object evalExpr(Expression e, RebecInstance[] allRebecs, int senderId) {
        if (e instanceof Literal) {
            return parseLiteral(((Literal) e).getLiteralValue());
        }

        if (e instanceof TermPrimary) {
            TermPrimary term = (TermPrimary) e;
            String n = term.getName();
            if ("sender".equals(n)) return senderId;
            if ("self".equals(n))   return this.id;
            int knownIdx = knownRebecNames.indexOf(n);
            if (knownIdx >= 0)      return knownRebecs[knownIdx];
            return vars.get(n);
        }

        if (e instanceof BinaryExpression) {
            BinaryExpression bin = (BinaryExpression) e;
            Object left  = evalExpr(bin.getLeft(),  allRebecs, senderId);
            Object right = evalExpr(bin.getRight(), allRebecs, senderId);
            return evalBinary(bin.getOperator(), left, right);
        }

        if (e instanceof UnaryExpression) {
            UnaryExpression u = (UnaryExpression) e;
            Object operand = evalExpr(u.getExpression(), allRebecs, senderId);
            if ("!".equals(u.getOperator())) return !toBoolean(operand);
            if ("-".equals(u.getOperator())) return -toInt(operand);
        }

        if (e instanceof DotPrimary) {
            // Evaluating a DotPrimary as an expression — treat as a send statement
            executeSend((DotPrimary) e, allRebecs, senderId);
            return null;
        }

        throw new UnsupportedOperationException(
                "Unsupported expression type: " + e.getClass().getSimpleName());
    }

    private void executeSend(DotPrimary dot, RebecInstance[] allRebecs, int senderId) {
        int receiverId = toInt(evalExpr(dot.getLeft(), allRebecs, senderId));
        TermPrimary right   = (TermPrimary) dot.getRight();
        String      msgName = right.getName();

        List<Expression> argExprs = (right.getParentSuffixPrimary() == null)
                ? Collections.emptyList()
                : right.getParentSuffixPrimary().getArguments();

        int[] params = new int[argExprs.size()];
        for (int i = 0; i < argExprs.size(); i++)
            params[i] = toInt(evalExpr(argExprs.get(i), allRebecs, senderId));

        allRebecs[receiverId].enqueue(new Message(msgName, this.id, params));
    }

    private static Object evalBinary(String op, Object left, Object right) {
        switch (op) {
            case "==": return Objects.equals(left, right);
            case "!=": return !Objects.equals(left, right);
            case "&&": return toBoolean(left) && toBoolean(right);
            case "||": return toBoolean(left) || toBoolean(right);
            case "+":  return toInt(left) + toInt(right);
            case "-":  return toInt(left) - toInt(right);
            case "*":  return toInt(left) * toInt(right);
            case "/":  return toInt(left) / toInt(right);
            case "%":  return toInt(left) % toInt(right);
            case "<":  return toInt(left) <  toInt(right);
            case "<=": return toInt(left) <= toInt(right);
            case ">":  return toInt(left) >  toInt(right);
            case ">=": return toInt(left) >= toInt(right);
            default:   throw new UnsupportedOperationException("Unknown operator: " + op);
        }
    }

    private static Object parseLiteral(String s) {
        if ("true".equals(s))  return Boolean.TRUE;
        if ("false".equals(s)) return Boolean.FALSE;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        throw new UnsupportedOperationException("Unknown literal: " + s);
    }

    private static boolean toBoolean(Object v) { return Boolean.TRUE.equals(v); }
    private static int     toInt(Object v)     { return ((Number) v).intValue(); }

    private Object[] snapshotVars() {
        return stateVarNames.stream().map(vars::get).toArray();
    }

    private void restoreVars(Object[] vals) {
        for (int i = 0; i < stateVarNames.size(); i++)
            vars.put(stateVarNames.get(i), vals[i]);
    }

    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return name + "(id=" + id + ", action=" + enabledAction() + ")";
    }
}
