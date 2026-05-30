# Workflow: Performance Investigation

Process for investigating performance regressions or optimizing hot paths in Spector Search.

## Trigger

When investigating slow queries, memory regressions, SIMD inefficiencies, or the user requests performance analysis.

## Steps

### 1. Identify the Hot Path

Determine which component is slow:
- **Search latency** → `spector-index` (HNSW traversal, SIMD similarity)
- **Ingestion throughput** → `spector-engine` / `spector-ingestion` pipeline
- **Memory operations** → `spector-memory` (WAL writes, synapse lookup)
- **Startup time** → `spector-runtime` / `spector-engine` (index loading)

### 2. Baseline Benchmark

```bash
mvn package -pl spector-bench -DskipTests
java --add-modules jdk.incubator.vector \
  -jar spector-bench/target/benchmarks.jar \
  {BenchmarkClass} -f 1 -wi 3 -i 5
```

Record baseline numbers before any changes.

### 3. Profile

Use JFR (Java Flight Recorder):
```bash
java -XX:StartFlightRecording=filename=profile.jfr,duration=60s ...
```

Look for:
- Heap allocations in hot path (`new float[]`, `toArray()`, boxing)
- Lock contention (`ReentrantLock` wait time)
- Virtual thread pinning (should not happen if no `synchronized`)
- Unnecessary `MemorySegment` copies

### 4. Analyze Code

Check against performance rules:

- [ ] No heap allocations in similarity/search loops
- [ ] `MemorySegment` slices used (not `.toArray()`)
- [ ] SIMD uses `FloatVector.SPECIES_PREFERRED`
- [ ] SIMD loop bound via `SPECIES.loopBound()` with scalar tail
- [ ] `VectorMask` for partial-lane handling (not branching)
- [ ] Arena lifecycle correct (`ofShared` vs `ofConfined`)
- [ ] Buffers reused, not allocated per-call
- [ ] No `String.format()` in hot loops (use SLF4J parameterized logging)

### 5. Optimize

Apply the fix following coding standards. Common optimizations:

| Problem | Fix |
|---|---|
| `float[]` allocation in loop | Pre-allocate and reuse buffer |
| `segment.toArray()` | Use `segment.asSlice()` |
| Scalar similarity | Replace with SIMD `FloatVector` |
| `String.format` in loop | Move outside or use SLF4J `{}` |
| Synchronized lock | Replace with `ReentrantLock` |

### 6. Benchmark After

Run the same benchmark from Step 2. Compare:
- Throughput (ops/sec)
- Latency (avg, p99)
- Allocation rate (bytes/op)

### 7. Commit

```
perf({module}): <description>

Before: {N} ops/sec, p99={X}ms
After:  {M} ops/sec, p99={Y}ms
Improvement: {Z}%
```

Include JMH numbers in commit body. This is mandatory for `perf:` commits.
