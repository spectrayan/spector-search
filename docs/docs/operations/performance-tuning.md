# 🏎️ Performance Tuning

> **Spector Search delivers sub-millisecond latency out of the box — but there's always room to optimize for your specific workload.** This page covers benchmarks, tuning strategies, and the science of finding the right recall/latency/memory trade-off.

---

## 📊 Benchmark Summary

> All benchmarks measured on a 24-core x86 machine, AVX2 256-bit, Java 25, ZGC, 128-dimensional vectors.

### ⚡ SIMD Kernel Latency

| Dimension | Cosine P50 | Cosine P99 | Dot Product P50 | Dot Product P99 |
|-----------|-----------|-----------|----------------|----------------|
| 32 | 500 ns | 1,500 ns | 200 ns | 400 ns |
| 128 | <100 ns | 100 ns | 100 ns | 1,300 ns |
| 384 | ~100 ns | 100 ns | ~100 ns | 100 ns |
| 768 | ~100 ns | 100 ns | ~100 ns | 100 ns |

> [!NOTE]
> Values at 384+ are at `System.nanoTime()` resolution floor. JMH confirms millions of ops/sec.

### 🔍 Search Latency (128-dim, top-10)

| Scale | Keyword (BM25) | Vector (HNSW) | Hybrid (RRF) |
|-------|---------------|---------------|--------------|
| **10K docs** | 0.15 ms / 0.43 ms p99 | **0.05 ms** / 0.16 ms p99 | 0.14 ms / 0.24 ms p99 |
| **50K docs** | 0.35 ms / 0.55 ms p99 | **0.04 ms** / 0.05 ms p99 | 0.25 ms / 0.44 ms p99 |
| **100K docs** | 0.60 ms / 1.12 ms p99 | **0.05 ms** / 0.06 ms p99 | 0.47 ms / 0.64 ms p99 |

### 🚀 Search Throughput (queries/sec)

| Scale | Keyword | Vector | Hybrid | Vector top-100 |
|-------|---------|--------|--------|----------------|
| 10K | 6,806 | **22,152** | 7,318 | 17,573 |
| 50K | 2,854 | **22,808** | 4,038 | 12,271 |
| 100K | 1,679 | **20,246** | 2,143 | 10,174 |

### 📥 Ingestion Throughput

| Dataset Size | Time | Rate | Memory |
|-------------|------|------|--------|
| 10K | 2.1s | **4,589 docs/s** | +20 MB |
| 50K | 16.2s | **3,079 docs/s** | +94 MB |
| 100K | 45.5s | **2,194 docs/s** | +188 MB |

### 🧵 Concurrency Scaling (50K docs, Hybrid Search)

| Threads | Throughput | Avg Latency | Scaling Factor |
|---------|-----------|-------------|----------------|
| 1 | 4,108 ops/s | 0.24 ms | 1.0× |
| 4 | 12,344 ops/s | 0.32 ms | **3.0×** |
| 8 | 17,628 ops/s | 0.44 ms | **4.3×** |
| 16 | 18,324 ops/s | 0.79 ms | **4.5×** |

---

## 🧪 Running Benchmarks

### Full Benchmark Suite

```bash
mvn -pl spector-bench exec:java
```

> [!TIP]
> Generates an HTML report at `spector-bench/target/performance-report.html`

### Specific Benchmarks

```bash
# SIMD kernels only
mvn -pl spector-bench exec:java -Dexec.args="SimdKernelBenchmark"

# HNSW index operations
mvn -pl spector-bench exec:java -Dexec.args="HnswBenchmark"

# Concurrency scaling
mvn -pl spector-bench exec:java -Dexec.args="ConcurrencyBenchmark"
```

### JSON Output for CI

```bash
mvn -pl spector-bench exec:java -Dexec.args="-rf json -rff results.json"
```

### 📏 Baseline Regression Detection

```bash
# Generate baseline
mvn -pl spector-bench exec:java -Dexec.args="--baseline"

# Compare against baseline
mvn -pl spector-bench exec:java -Dexec.args="--compare"
```

---

## 🎛️ Tuning Strategies

### 🎯 Maximize Recall

Goal: recall@10 ≥ 95%

```java
var config = SpectorConfig.DEFAULT
    .withM(32)                  // More connections
    .withEfConstruction(400)    // Better graph quality
    .withEfSearch(200);         // Wider search beam
```

Trade-offs: 2× memory, ~3× build time, ~2× query latency.

---

### ⚡ Minimize Latency

Goal: p99 < 0.5ms

```java
var config = SpectorConfig.DEFAULT
    .withM(12)
    .withEfConstruction(100)
    .withEfSearch(30);
```

Trade-offs: Lower recall (~80% recall@10), but sub-millisecond guaranteed.

---

### 🚀 Maximize Throughput

Goal: Maximum queries/sec under concurrent load

```java
var config = SpectorConfig.DEFAULT
    .withM(16)               // Balanced
    .withEfSearch(50)        // Not too high
    .withGpu(true);          // Batch processing
```

Key factors:
- Virtual threads handle concurrency automatically
- Keep `efSearch` moderate to reduce per-query work
- Enable GPU for batch workloads
- Use IVF-PQ for large datasets (reduced memory = better cache behavior)

---

### 💾 Minimize Memory

Goal: Fit large datasets in limited RAM

```java
var config = SpectorConfig.DEFAULT
    .withM(8)                // Fewer connections
    .withEfConstruction(100);
// Use IVF-PQ for 32× vector compression
```

**Memory per document (384-dim):**

| Mode | Per Vector | 1M vectors |
|------|-----------|------------|
| Float32 | ~1.8 KB | ~1.8 GB |
| INT8 | ~640 bytes | ~640 MB |
| IVF-PQ | ~288 bytes | ~288 MB |

---

## 📈 Parameter Tuning Guide

### HNSW: efSearch vs Recall vs Latency

| efSearch | Recall@10 | Avg Latency | Notes |
|----------|-----------|-------------|-------|
| 10 | ~70% | 0.02 ms | Too low for most uses |
| 30 | ~85% | 0.03 ms | Fast, moderate recall |
| **50** | **~90%** | **0.05 ms** | **Default, good balance** |
| 100 | ~95% | 0.10 ms | High recall |
| 200 | ~98% | 0.20 ms | Near-perfect recall |
| 500 | ~99.5% | 0.50 ms | Diminishing returns |

### IVF-PQ: nprobe vs Recall

| nprobe | Recall@10 | Relative Latency |
|--------|-----------|-----------------|
| 1 | ~40% | 1× |
| 4 | ~70% | 4× |
| 8 | ~85% | 8× |
| 16 | ~92% | 16× |
| 32 | ~97% | 32× |

---

## 📐 Scaling Strategies

### ⬆️ Vertical Scaling

- **Add CPU cores** → Linear throughput scaling (up to ~4.5× at 16 threads)
- **Add RAM** → Support larger capacity without IVF-PQ compression
- **Add GPU** → Massive batch throughput (14× at 1024 concurrent queries)

### ➡️ Horizontal Scaling (Distributed Mode)

- **Add nodes** → Linear throughput scaling per shard
- Rule of thumb: 100K–500K docs per shard
- See [Distributed Mode](../architecture/distributed-mode.md) for cluster setup

---

## ☕ JVM Tuning

Recommended JVM arguments for production:

```bash
java \
  --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xmx4g \
  -Xms4g \
  -jar spector-server.jar
```

| Argument | Purpose |
|----------|---------|
| `--add-modules jdk.incubator.vector` | Required for SIMD acceleration |
| `--enable-native-access=ALL-UNNAMED` | Required for Panama FFM (GPU, mmap) |
| `-XX:+UseZGC` | Low-pause GC (vectors are off-heap) |
| `-XX:+ZGenerational` | Generational ZGC for better throughput |
| `-Xmx4g -Xms4g` | Fixed heap avoids resize pauses |

> [!TIP]
> Since all vectors live off-heap, GC pressure is minimal. The heap primarily holds the HNSW graph structure and BM25 inverted index.

---

## 🔗 See Also

- [Configuration Guide](../configuration/parameters.md) — All parameters with ranges
- [Core Concepts](../architecture/core-concepts.md) — How algorithms affect performance
- [GPU Acceleration](../architecture/gpu-acceleration.md) — GPU-specific performance
- [Distributed Mode](../architecture/distributed-mode.md) — Scaling across nodes
