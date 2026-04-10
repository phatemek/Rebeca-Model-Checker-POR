package rebec;

public enum StepResult {
    OK,
    DISABLED,           // rebec's queue was empty — no action taken
    QUEUE_OVERFLOW,     // a send during this step exceeded some rebec's queue capacity
    ASSERTION_FAILED    // an assertion() call inside a msgsrv evaluated to false
}
