import engine.ModelChecker;
import rebec.RebecInstance;
import translator.RebecTranslator;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

public class Main {
    public static void main(String[] args) {
        boolean usePOR         = false;
        boolean detectDeadlock = true;
        String  modelPath      = "src/RebecaModels/diningPhilosophers.rebeca";
        String  outputPath     = null;

        for (int i = 0; i < args.length; i++) {
            if      ("-por".equals(args[i]))        usePOR         = true;
            else if ("-noDeadlock".equals(args[i])) detectDeadlock = false;
            else if ("-model".equals(args[i]))      modelPath      = args[++i];
            else if ("-output".equals(args[i]))     outputPath     = args[++i];
        }

        RebecInstance[] instances = RebecTranslator.translateFile(new File(modelPath));
        if (instances == null) return;

        System.out.println("Model:          " + modelPath);
        System.out.println("POR:            " + usePOR);
        System.out.println("Deadlock check: " + detectDeadlock);
        if (outputPath != null)
            System.out.println("State log:      " + outputPath);
        System.out.println();

        ModelChecker mc = new ModelChecker(instances, usePOR, detectDeadlock);
        if (outputPath != null) {
            try {
                mc.setStateLog(new PrintWriter(outputPath));
            } catch (IOException e) {
                System.err.println("Cannot open output file: " + e.getMessage());
            }
        }
        mc.check();
    }
}
