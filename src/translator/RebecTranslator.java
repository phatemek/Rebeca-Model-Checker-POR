package translator;

import model.Message;
import org.rebecalang.compiler.CompilerConfig;
import org.rebecalang.compiler.modelcompiler.RebecaModelCompiler;
import org.rebecalang.compiler.modelcompiler.SymbolTable;
import org.rebecalang.compiler.modelcompiler.corerebeca.objectmodel.*;
import org.rebecalang.compiler.utils.CompilerExtension;
import org.rebecalang.compiler.utils.CoreVersion;
import org.rebecalang.compiler.utils.ExceptionContainer;
import org.rebecalang.compiler.utils.Pair;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import rebec.RebecInstance;

import java.io.File;
import java.util.*;

/**
 * Reads a parsed RebecaModel and produces a fully-wired RebecInstance[].
 *
 * After translate() returns:
 *   - Each instance has its knownRebecs[] set to the IDs of its bound rebecs.
 *   - Each instance has an "initial" message enqueued (self-send, senderId == own id).
 */
public class RebecTranslator {

    /**
     * Parses the given .rebeca file and returns a fully-wired RebecInstance[].
     * Prints parse errors to stderr and returns null if compilation fails.
     */
    public static RebecInstance[] translateFile(File modelFile) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(CompilerConfig.class)) {

            RebecaModelCompiler compiler   = ctx.getBean(RebecaModelCompiler.class);
            ExceptionContainer  exceptions = ctx.getBean(ExceptionContainer.class);

            Set<CompilerExtension> extensions = EnumSet.noneOf(CompilerExtension.class);
            Pair<RebecaModel, SymbolTable> out =
                    compiler.compileRebecaFile(modelFile, extensions, CoreVersion.CORE_2_0);

            if (out == null || !exceptions.exceptionsIsEmpty()) {
                System.err.println("Parse errors in " + modelFile.getName() + ":");
                exceptions.print(System.err);
                return null;
            }

            return translate(out.getFirst());
        }
    }

    public static RebecInstance[] translate(RebecaModel model) {
        List<MainRebecDefinition> mainDefs =
                model.getRebecaCode().getMainDeclaration().getMainRebecDefinition();

        // class name → ReactiveClassDeclaration
        Map<String, ReactiveClassDeclaration> classByName = new HashMap<>();
        for (ReactiveClassDeclaration rcd : model.getRebecaCode().getReactiveClassDeclaration()) {
            classByName.put(rcd.getName(), rcd);
        }

        // instance name → integer ID  (position in the main block)
        Map<String, Integer> idByName = new LinkedHashMap<>();
        for (int i = 0; i < mainDefs.size(); i++) {
            idByName.put(mainDefs.get(i).getName(), i);
        }

        RebecInstance[] instances = new RebecInstance[mainDefs.size()];

        // ---- Pass 1: create instances ----
        for (int i = 0; i < mainDefs.size(); i++) {
            MainRebecDefinition    def = mainDefs.get(i);
            ReactiveClassDeclaration rcd = classByName.get(def.getType().getTypeName());

            List<String>         knownRebecNames = extractVarNames(rcd.getKnownRebecs());
            List<String>         stateVarNames   = extractVarNames(rcd.getStatevars());
            Map<String, Object>  initialVars     = defaultVars(rcd.getStatevars());

            Map<String, MsgsrvDeclaration> msgsrvMap = new LinkedHashMap<>();
            for (MsgsrvDeclaration m : rcd.getMsgsrvs()) {
                msgsrvMap.put(m.getName(), m);
            }

            instances[i] = new RebecInstance(
                    i,
                    def.getName(),
                    def.getType().getTypeName(),
                    rcd.getQueueSize(),
                    knownRebecNames,
                    stateVarNames,
                    initialVars,
                    msgsrvMap
            );
        }

        // ---- Pass 2: wire knownRebecs[] and enqueue initial ----
        for (int i = 0; i < mainDefs.size(); i++) {
            MainRebecDefinition def      = mainDefs.get(i);
            List<Expression>    bindings = def.getBindings();

            int[] knownIds = new int[bindings.size()];
            for (int j = 0; j < bindings.size(); j++) {
                String boundName = ((TermPrimary) bindings.get(j)).getName();
                knownIds[j] = idByName.get(boundName);
            }
            instances[i].knownRebecs = knownIds;
            List<Expression> ctorArgs = def.getArguments();
            Object[] initParams = new Object[ctorArgs.size()];
            for (int j = 0; j < ctorArgs.size(); j++)
                initParams[j] = evalConstExpr(ctorArgs.get(j));
            instances[i].enqueue(new Message("initial", i, initParams));
        }

        return instances;
    }

    // -------------------------------------------------------------------------

    /** Flattens all VariableDeclarators from a list of FieldDeclarations into an ordered name list. */
    private static List<String> extractVarNames(List<FieldDeclaration> fields) {
        List<String> names = new ArrayList<>();
        for (FieldDeclaration fd : fields) {
            for (VariableDeclarator vd : fd.getVariableDeclarators()) {
                names.add(vd.getVariableName());
            }
        }
        return names;
    }

    /** Builds a var-name → default-value map from statevars field declarations. */
    private static Map<String, Object> defaultVars(List<FieldDeclaration> statevars) {
        Map<String, Object> vars = new LinkedHashMap<>();
        for (FieldDeclaration fd : statevars) {
            Object defaultVal;
            if (fd.getType() instanceof ArrayType) {
                int size = ((ArrayType) fd.getType()).getDimensions().get(0);
                defaultVal = new int[size];
            } else {
                defaultVal = defaultForType(fd.getType().getTypeName());
            }
            for (VariableDeclarator vd : fd.getVariableDeclarators()) {
                vars.put(vd.getVariableName(), defaultVal);
            }
        }
        return vars;
    }

    /** Evaluates a compile-time constant expression used as a constructor argument. */
    private static Object evalConstExpr(Expression e) {
        if (e instanceof CastExpression)
            return evalConstExpr(((CastExpression) e).getExpression());
        if (e instanceof Literal) {
            String s = ((Literal) e).getLiteralValue();
            if ("true".equals(s))  return Boolean.TRUE;
            if ("false".equals(s)) return Boolean.FALSE;
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        throw new UnsupportedOperationException("Unsupported constructor arg: " + e.getClass().getSimpleName());
    }

    private static Object defaultForType(String typeName) {
        switch (typeName) {
            case "boolean":           return Boolean.FALSE;
            case "int":
            case "byte":
            case "short":
            case "long":              return 0;
            default:                  return null;
        }
    }
}
