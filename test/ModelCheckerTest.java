import engine.ModelChecker;
import org.junit.jupiter.api.Test;
import rebec.RebecInstance;
import translator.RebecTranslator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCheckerTest {

    // -------------------------------------------------------------------------
    // Dining Philosophers — deadlock-free
    // -------------------------------------------------------------------------

    @Test
    void diningPhilosophers_noViolations() {
        Result r = run("diningPhilosophers.rebeca", false, true);
        assertTrue(r.violations.isEmpty(), "expected no violations");
    }

    @Test
    void diningPhilosophers_porReducesStateSpace() {
        Result plain = run("diningPhilosophers.rebeca", false, true);
        Result por   = run("diningPhilosophers.rebeca", true,  true);
        assertTrue(por.states <= plain.states,
                "POR (" + por.states + ") should explore ≤ states than plain (" + plain.states + ")");
    }

    // -------------------------------------------------------------------------
    // Dining Philosophers — deadlock
    // -------------------------------------------------------------------------

    @Test
    void diningPhilosophersDeadlock_deadlockDetected() {
        Result r = run("diningPhilosophersDeadlock.rebeca", false, true);
        assertTrue(hasType(r, "DEADLOCK"), "expected DEADLOCK violation");
    }

    @Test
    void diningPhilosophersDeadlock_porStillDetectsDeadlock() {
        Result r = run("diningPhilosophersDeadlock.rebeca", true, true);
        assertTrue(hasType(r, "DEADLOCK"), "POR must still detect DEADLOCK");
    }

    // -------------------------------------------------------------------------
    // Queue overflow
    // -------------------------------------------------------------------------

    @Test
    void queueOverflow_overflowDetected() {
        Result r = run("queueOverflow.rebeca", false, false);
        assertTrue(hasType(r, "QUEUE_OVERFLOW"), "expected QUEUE_OVERFLOW violation");
    }

    @Test
    void queueOverflow_porStillDetectsOverflow() {
        Result r = run("queueOverflow.rebeca", true, false);
        assertTrue(hasType(r, "QUEUE_OVERFLOW"), "POR must still detect QUEUE_OVERFLOW");
    }

    // -------------------------------------------------------------------------
    // Assertion violation
    // -------------------------------------------------------------------------

    @Test
    void assertionViolation_assertionDetected() {
        Result r = run("assertionViolation.rebeca", false, false);
        assertTrue(hasType(r, "ASSERTION_FAILED"), "expected ASSERTION_FAILED violation");
    }

    @Test
    void assertionViolation_porStillDetectsAssertion() {
        Result r = run("assertionViolation.rebeca", true, false);
        assertTrue(hasType(r, "ASSERTION_FAILED"), "POR must still detect ASSERTION_FAILED");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final String MODEL_DIR = "src/RebecaModels/";

    private Result run(String modelFile, boolean usePOR, boolean detectDeadlock) {
        PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            RebecInstance[] instances = RebecTranslator.translateFile(new File(MODEL_DIR + modelFile));
            assertNotNull(instances, "failed to parse " + modelFile);
            ModelChecker mc = new ModelChecker(instances, usePOR, detectDeadlock);
            List<String> violations = mc.check();
            return new Result(violations, mc.getStatesExplored());
        } finally {
            System.setOut(original);
        }
    }

    private boolean hasType(Result result, String type) {
        return result.violations.stream().anyMatch(v -> v.startsWith(type));
    }

    private record Result(List<String> violations, int states) {}
}
