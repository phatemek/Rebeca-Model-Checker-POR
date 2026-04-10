# Rebeca Model Checker — Partial Order Reduction

A DFS-based model checker for the [Rebeca](https://rebeca-lang.org) actor language.  
Checks for **deadlock**, **queue overflow**, and **assertion violations**.  
Optionally applies **static partial order reduction (POR)** to reduce the explored state space.

---

## Compile

From the project root:

```bash
javac -cp "src/Dependencies/compiler-2.30.jar:$(find src/Dependencies -name '*.jar' | tr '\n' ':')" \
      -sourcepath src \
      -d out/production/PartialOrderReduction \
      src/Main.java
```

---

## Run

```bash
java -cp "out/production/PartialOrderReduction:src/Dependencies/compiler-2.30.jar:$(find src/Dependencies -name '*.jar' | tr '\n' ':')" \
     Main [flags]
```

### Flags

| Flag | Argument | Description |
|------|----------|-------------|
| `-model` | `<path>` | Path to the `.rebeca` model file to check. Defaults to `src/RebecaModels/diningPhilosophers.rebeca`. |
| `-por` | — | Enable static partial order reduction. The checker statically marks safe actions before the DFS begins and uses singleton ample sets where possible, reducing the number of explored states without affecting correctness. |
| `-noDeadlock` | — | Disable deadlock detection. Useful when a model is designed to terminate naturally (all queues drain to empty) and that termination should not be reported as a violation. |

---

## Examples

Check the deadlock-free dining philosophers (no POR):
```bash
java -cp "out/production/PartialOrderReduction:src/Dependencies/compiler-2.30.jar:$(find src/Dependencies -name '*.jar' | tr '\n' ':')" \
     Main -model src/RebecaModels/diningPhilosophers.rebeca
```

Same model with POR enabled:
```bash
java -cp "out/production/PartialOrderReduction:src/Dependencies/compiler-2.30.jar:$(find src/Dependencies -name '*.jar' | tr '\n' ':')" \
     Main -model src/RebecaModels/diningPhilosophers.rebeca -por
```

Check the deadlocking version:
```bash
java -cp "out/production/PartialOrderReduction:src/Dependencies/compiler-2.30.jar:$(find src/Dependencies -name '*.jar' | tr '\n' ':')" \
     Main -model src/RebecaModels/diningPhilosophersDeadlock.rebeca -por
```

---

## Models

| File | Description |
|------|-------------|
| `src/RebecaModels/diningPhilosophers.rebeca` | 3 philosophers, deadlock-free. Fork bindings use resource ordering to break the circular wait. |
| `src/RebecaModels/diningPhilosophersDeadlock.rebeca` | 3 philosophers, deadlocks. Fully circular fork binding — each philosopher grabs their left fork first, forming a circular wait. |
| `src/RebecaModels/queueOverflow.rebeca` | A Dispatcher sends 5 tasks to a Worker whose queue holds only 3. Triggers queue overflow detection. |
