import engine.ModelChecker;
import rebec.RebecInstance;
import translator.RebecTranslator;

import java.io.File;

public class Main {
    public static void main(String[] args) {
        boolean usePOR         = false;
        boolean detectDeadlock = true;
        String  modelPath      = "src/RebecaModels/diningPhilosophers.rebeca";

        for (int i = 0; i < args.length; i++) {
            if      ("-por".equals(args[i]))        usePOR         = true;
            else if ("-noDeadlock".equals(args[i])) detectDeadlock = false;
            else if ("-model".equals(args[i]))      modelPath      = args[++i];
        }

        RebecInstance[] instances = RebecTranslator.translateFile(new File(modelPath));
        if (instances == null) return;

        System.out.println("Model:          " + modelPath);
        System.out.println("POR:            " + usePOR);
        System.out.println("Deadlock check: " + detectDeadlock);
        System.out.println();

        new ModelChecker(instances, usePOR, detectDeadlock).check();
    }
}
