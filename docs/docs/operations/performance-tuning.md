# 🏎️ Performance Tuning

> **Spector Search delivers sub-millisecond latency out of the box — but there's always room to optimize for your specific workload.** This page covers benchmarks, tuning strategies, and the science of finding the right recall/latency/memory trade-off.

---

## 📊 Benchmark Summary

> All benchmarks measured on a 24-core x86 machine (Windows 11, AMD), AVX2 256-bit, Java 25, ZGC, using clustered vectors (realistic distribution). Numbers represent actual measured results — run `mvn -pl spector-bench exec:java` to reproduce on your hardware.

> [!NOTE]
> **Methodology:** Benchmarks use 200 measurement iterations with 50 warmup iterations per scenario. Vectors are generated with realistic cluster structure (50 clusters with Gaussian noise). Documents contain 200–1500 words with paragraph structure. Recall is measured against brute-force ground truth. Your results may vary ±20% depending on CPU model, OS scheduling, background load, and thermal throttling.

### ⚡ SIMD Kernel Latency

| Dimension | Cosine P50 | Cosine P99 | Dot Product P50 | Dot Product P99 |
|-----------|-----------|-----------|----------------|----------------|
| 32 | 500 ns | 1,500 ns | 200 ns | 400 ns |
| 128 | <100 ns | 100 ns | 100 ns | 1,300 ns |
| 384 | ~100 ns | 100 ns | ~100 ns | 100 ns |
| 768 | ~100 ns | 100 ns | ~100 ns | 100 ns |

> [!NOTE]
> Values at 384+ are at `System.nanoTime()` resolution floor. JMH confirms millions of ops/sec.

### 🔍 Search Latency (128-dim, top-10, clustered vectors)

| Scale | Keyword (BM25) | Vector (HNSW) | Hybrid (RRF) |
|-------|---------------|---------------|--------------|
| **10K docs** | 0.19 ms / 3.79 ms p99 | **0.05 ms** / 0.10 ms p99 | 0.17 ms / 0.37 ms p99 |
| **50K docs** | 0.42 ms / 0.68 ms p99 | **0.09 ms** / 0.19 ms p99 | 0.50 ms / 0.81 ms p99 |
| **100K docs** | 0.98 ms / 1.39 ms p99 | **0.13 ms** / 0.26 ms p99 | 1.01 ms / 1.22 ms p99 |

### 🚀 Search Throughput (queries/sec)

| Scale | Keyword | Vector | Hybrid |
|-------|---------|--------|--------|
| 10K | 5,194 | **18,824** | 5,828 |
| 50K | 2,406 | **10,980** | 1,988 |
| 100K | 1,019 | **7,556** | 994 |

### 📥 Ingestion Throughput

| Dataset Size | Time | Rate | Memory |
|-------------|------|------|--------|
| 10K | 2.5s | **3,931 docs/s** | +19 MB |
| 50K | 15.1s | **3,308 docs/s** | +93 MB |
| 100K | 38.2s | **2,618 docs/s** | +187 MB |

### 🧵 Concurrency Scaling (50K docs, 384-dim, Hybrid Search)

| Threads | Throughput | Avg Latency | Scaling Factor |
|---------|-----------|-------------|----------------|
| 1 | 3,739 ops/s | 0.26 ms | 1.0× |
| 4 | 10,317 ops/s | 0.37 ms | **2.8×** |
| 8 | 11,812 ops/s | 0.58 ms | **3.2×** |
| 16 | 14,022 ops/s | 1.00 ms | **3.7×** |

> [!NOTE]
> Concurrency scaling is measured with 384-dim vectors (production-realistic). 128-dim shows higher absolute throughput but the scaling factor is similar. Individual HNSW queries are sequential — scaling comes from serving multiple queries concurrently.

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

> [!NOTE]
> Recall values below are measured with uniform random vectors (best case). Real embedding distributions with cluster structure may show lower recall at the same efSearch — increase efSearch to 100–200 for production workloads with real embeddings.

| efSearch | Recall@10 (random) | Recall@10 (clustered) | Avg Latency | Notes |
|----------|-----------|-----------|-------------|-------|
| 10 | ~70% | ~30-40% | 0.02 ms | Too low for most uses |
| 30 | ~85% | ~50-60% | 0.03 ms | Fast, moderate recall |
| **64** | **~90%** | **~50-65%** | **0.05 ms** | **Default** |
| 100 | ~95% | ~70-80% | 0.10 ms | Good for production |
| 200 | ~98% | ~85-90% | 0.20 ms | High recall |
| 500 | ~99.5% | ~95%+ | 0.50 ms | Near-perfect |

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

- **Add CPU cores** → Concurrent throughput scaling (up to ~3.7× at 16 threads measured)
- **Add RAM** → Support larger capacity without IVF-PQ compression
- **Add GPU** → 4× brute-force search speedup at 100K+ vectors (data resident in VRAM)

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