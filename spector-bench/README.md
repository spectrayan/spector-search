# spector-bench 📊

> **JMH microbenchmarks, performance sweeps, and large-scale real-embedding performance runners.**

`spector-bench` handles empirical performance testing, SIMD kernel validation, and large-scale index sweeps for Spector. It is designed to run locally, generating interactive HTML reports with latency charts.

---

## 🏗️ Core Architecture & Runners

1. **JMH Microbenchmarks (`SpectorMicrobench`):** Microsecond-level isolation checks for the Panama Vector similarity kernels (AVX2 vs. AVX-512 vs. ARM NEON).
2. **Real-Embedding Sweeps (`RealEmbeddingScaleBench`):** Implements multi-centroid sweeps ($C \in \{32, 64, 128, 256\}$) using real Qwen3 text embeddings from local Ollama providers.
3. **Promotion Benchmarks (`SpectorIndexPromotionBench`):** Head-to-head comparisons of Flat Shard SIMD scans vs. Promoted HNSW Shards at 100K scale.

---

## 🚀 Running Benchmarks

### Generate Dependencies Classpath
Ensure the classpath is compiled before running:
```bash
mvn clean compile -pl spector-bench
```

### Running the Real-Embedding Scale Sweep
Run Ollama qwen3-embedding benchmarking at a scale of 10,000 vectors:
```powershell
$cp = "spector-bench/target/classes;" + (Get-Content spector-bench/target/cp.txt)
java --add-modules jdk.incubator.vector -Xmx12g -cp $cp com.spectrayan.spector.bench.RealEmbeddingScaleBench 10000
```

### Running the Shard Promotion Comparison
Run Flat vs Promoted HNSW comparison at 100K scale:
```powershell
java --add-modules jdk.incubator.vector -Xmx12g -cp $cp com.spectrayan.spector.bench.SpectorIndexPromotionBench
```
