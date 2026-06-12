# Supplemental Agent Rules

This document outlines supplemental rules regarding **State Management**, **Error Handling**, and **Environment/Config Variables** that fill critical operational gaps in the standard `rules.md` and `coding-standards/SKILL.md` configurations. 

---

## 1. Concurrency & State Management

Because Spector is built on virtual threads (Project Loom) and runs with high-concurrency off-heap operations, standard state management practices can result in virtual thread pinning, memory leaks, or race conditions.

### Virtual Thread Pinning Avoidance
*   **NEVER** use `synchronized` blocks or synchronized methods. This pins the virtual thread to its carrier thread, defeating the purpose of Loom.
*   **ALWAYS** use `ReentrantLock` for exclusive write locks, or `StampedLock` for high-throughput read-heavy components (e.g., in-memory index or query routing).
*   **ALWAYS** place lock acquisition outside the `try` block, and release in the `finally` block.

```java
// ✅ CORRECT — StampedLock for optimistic reads
private final StampedLock stateLock = new StampedLock();
private double score;

public double getScore() {
    long stamp = stateLock.tryOptimisticRead();
    double currentScore = score;
    if (!stateLock.validate(stamp)) {
        stamp = stateLock.readLock();
        try {
            currentScore = score;
        } finally {
            stateLock.unlockRead(stamp);
        }
    }
    return currentScore;
}
```

### Component Lifecycle State Transitions
Components (e.g., `SpectorEngine`, `SpectorMemory`, `MemoryWal`) must maintain a thread-safe, predictable lifecycle state (e.g., `NEW`, `INITIALIZED`, `RUNNING`, `STOPPED`).
*   **Atomic State Indicators**: Always use `AtomicReference<LifecycleState>` or a `volatile` state variable guarded by a `ReentrantLock`.
*   **CAS State Updates**: For lock-free state transitions, prefer `compareAndSet`.
*   **Check State Before Operations**: Every public method must check the active state of the component. Throw a `SpectorValidationException` or `SpectorInternalException` if the component is not in `RUNNING` state.

```java
public enum LifecycleState { NEW, RUNNING, CLOSED }

private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.NEW);

public void performOperation() {
    if (state.get() != LifecycleState.RUNNING) {
        throw new SpectorInternalException(ErrorCode.ILLEGAL_STATE, state.get());
    }
    // ...
}
```

### Off-Heap State Ownership
Spector relies on Project Panama FFM `MemorySegment` objects.
*   **Confined vs Shared Arenas**: If a component accesses memory on a single thread, use `Arena.ofConfined()`. If multiple virtual threads access off-heap memory concurrently, use `Arena.ofShared()`.
*   **Explicit Lifecycle Closing**: Classes holding off-heap state must implement `AutoCloseable` and release their underlying `Arena` in the `close()` method.
*   **Zero-Copy Slicing**: When sharing state across modules (e.g. search engine passing match data to memory), use `MemorySegment.asSlice()` instead of allocating a new heap array.

---

## 2. Advanced Error Handling & Logging

Spector's `SpectorException` framework is robust, but must be paired with thread-level and logging standards to avoid lost errors or log pollution.

### Virtual Thread Uncaught Exceptions
When spawning virtual threads using executors or raw builders, exceptions can be swallowed silently.
*   **Explicit Exception Handlers**: Configure all custom thread factories or executor pools with an `UncaughtExceptionHandler` that logs exceptions via SLF4J.
*   **Standard Handler Template**:

```java
Thread.Builder factory = Thread.ofVirtual().name("spector-worker-", 0);
factory.uncaughtExceptionHandler((thread, throwable) -> {
    log.error("[SPE-900-001] Uncaught exception in virtual thread: {}", thread.getName(), throwable);
});
```

### The Double-Logging Anti-Pattern
*   **NEVER** log an exception at the throw site AND again at the catch site.
*   **Rule**: If you catch an exception and rethrow it, do **not** log it. Let the ultimate caller at the boundary (e.g., CLI, gRPC server, or API controller) do the logging. Double logging populates log files with multiple redundant stack traces, destroying readability.

```java
// ❌ WRONG — logs error, then rethrows
try {
    fileStorage.write(bytes);
} catch (IOException e) {
    log.error("Write failed", e); // REDUNDANT
    throw new SpectorStorageException(filePath, e);
}

// ✅ CORRECT — Let the caller log or handle
try {
    fileStorage.write(bytes);
} catch (IOException e) {
    throw new SpectorStorageException(filePath, e);
}
```

---

## 3. Environment Variables & Configuration

Configuration must be flexible across local development, testing, and production clustering without exposing credentials or breaking build paths.

### Overrides & Environment Resolution
Spector uses Apache Commons Configuration for managing `.yml` variables.
*   **Variable Hierarchy**: Configuration parameter resolution order is:
    1.  System Properties (`-Dspector.port=9090`)
    2.  Environment Variables (`SPECTOR_PORT=9090`)
    3.  Local YAML Config (`spector-local.yml`)
    4.  Default YAML Config (`spector.yml.example`)
*   **Env Naming Convention**: Environment variable names must be upper-snake-case and correspond to the nested yaml path (e.g., `spector.embedding.ollama-url` overrides via `SPECTOR_EMBEDDING_OLLAMA_URL`).

### Security & Secrets Management
*   **NEVER** hardcode LLM/Ollama API keys, cloud credential paths, or cluster passwords into source files or configuration `.yml` files.
*   **Resolution Rules**: Use placeholders in default configurations to map secrets to environment variables.
    ```yaml
    spector:
      embedding:
        api-key: ${env:SPECTOR_EMBEDDING_API_KEY}
    ```
*   **Git Guards**: Ensure `spector-local.yml` and any files inside `.spector/` remain in `.gitignore`. If you need to test a specific private parameter local config, only modify `spector-local.yml`. Never edit `spector.yml.example` to include local paths.
