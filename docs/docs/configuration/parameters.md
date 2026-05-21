# ⚙️ Configuration Guide

> **Every knob, dial, and lever in Spector Search — with sensible defaults and expert tuning advice.** Whether you're optimizing for recall, latency, throughput, or memory, this page has you covered.

---

## 🎯 Core Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `dimensions` | 384 | 1–2048 | Vector dimensionality (must match your embedding model) |
| `capacity` | 100,000 | 1–10,000,000 | Maximum document count |
| `similarityFunction` | COSINE | COSINE, DOT_PRODUCT, EUCLIDEAN | Distance metric |

> [!TIP]
> **Quick model reference:**
> | Model | Dimensions |
> |-------|-----------|
> | all-MiniLM-L6-v2 | 384 |
> | e5-base-v2 | 768 |
> | text-embedding-ada-002 | 1536 |
> | nomic-embed-text | 768 |

**Choosing a similarity function:**
- **COSINE** — Normalized embeddings (most models)
- **DOT_PRODUCT** — Unnormalized embeddings where magnitude matters
- **EUCLIDEAN** — Spatial/geometric data

---

## 🗜️ Quantization Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `quantization` | NONE | NONE, SCALAR_INT8, SCALAR_INT4, SCALAR_INT2, IVF_PQ | Quantization type |
| `oversamplingFactor` | auto | 1–20 | Rescore oversampling (auto: INT8→1, INT4→3, INT2→5) |

### 🎛️ Quantization Profiles

| Priority | Type | Oversampling | Compression | Recall | Use Case |
|----------|------|--------------|-------------|--------|----------|
| 🎯 Max recall | INT8 | 1 (none) | 4× | 95–99% | Quality-critical search |
| ⚖️ Balanced | INT4 | 3 | 8× | 85–95% | Best compression/recall ratio |
| 💾 Memory-first | INT2 | 5 | 16× | 75–90% | Fit large datasets in RAM |
| 🚀 Billion-scale | IVF_PQ | — | 32× | 75–90% | Massive datasets |

> [!TIP]
> **Start with INT4** for most workloads. It gives 8× compression with excellent recall when paired with the default 3× rescore. Only go to INT2 if memory is the binding constraint, or IVF-PQ if you're at billion scale.

### Oversampling Tuning

The `oversamplingFactor` controls how many extra candidates are retrieved before rescoring with exact distances:

- **1** — No rescore (fastest, quantized scores returned directly)
- **3** — Good balance for INT4 (retrieves 3×K candidates, rescores to top-K)
- **5** — Recommended for INT2 (compensates for aggressive quantization)
- **10+** — Diminishing returns; use only if recall is still insufficient

```java
// INT4 with custom oversampling
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(50_000_000)
    .withQuantization(QuantizationType.SCALAR_INT4)
    .withRescore(5);  // Higher oversampling = better recall, slightly slower
```

---

## 🌐 HNSW Index Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `M` | 16 | 4–64 | Max connections per node per layer |
| `efConstruction` | 200 | 16–800 | Construction beam width |
| `efSearch` | 50 | 10–500 | Search beam width |

### 🎛️ Tuning Profiles

| Priority | M | efConstruction | efSearch | Trade-off |
|----------|---|----------------|----------|-----------|
| 🎯 High recall | 32–64 | 400–800 | 200–500 | More memory, slower build/search |
| ⚖️ Balanced | 16 | 200 | 50 | Good recall with fast performance |
| ⚡ Low latency | 8–12 | 100 | 20–30 | Faster search, lower recall |
| 💾 Memory-constrained | 4–8 | 100 | 20 | Minimal memory, lower recall |

> [!IMPORTANT]
> `efSearch` should be ≥ `topK` for meaningful results. Setting `efSearch < topK` means you're asking for more results than the algorithm explores.

---

## 📝 BM25 Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `k1` | 1.2 | 0.0–3.0 | Term frequency saturation |
| `b` | 0.75 | 0.0–1.0 | Document length normalization |

| Corpus Type | Recommended k1 | Recommended b |
|-------------|----------------|---------------|
| Short docs (tweets, titles) | 1.2 | 0.3 |
| Medium docs (articles) | 1.2 | 0.75 |
| Long docs (books, papers) | 1.5–2.0 | 0.75 |
| Mixed lengths | 1.2 | 0.5 |

---

## 🧬 Hybrid Search (RRF)

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `RRF k` | 60 | 1–1000 | Reciprocal Rank Fusion constant |

- `k = 60` — Original paper recommendation, works well generally
- Lower `k` (10–30) — Emphasizes top-ranked results more strongly
- Higher `k` (100+) — Flattens rank importance

---

## 🎮 GPU Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `gpuEnabled` | false | true/false | Enable CUDA GPU acceleration |
| `gpuMemoryBudget` | 256 MB | 256 MB – GPU max | Maximum GPU memory allocation |
| `gpuBatchWindow` | 10 ms | 1–100 ms | Batching window for query collection |
| `gpuMaxBatchSize` | 1024 | 1–1024 | Maximum queries per GPU batch |

> [!NOTE]
> Enable GPU for batch workloads with >10K vectors. Single queries are often faster on CPU SIMD due to zero kernel launch overhead.
> For INT4/INT2 quantization, GPU acceleration requires dimensions to be a multiple of 32. Non-aligned dimensions automatically fall back to CPU/SIMD.

---

## 🤖 Reranker Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `rerankerEnabled` | false | true/false | Enable LLM re-ranking via Ollama |
| `rerankerModel` | — | Any Ollama model | Model name (e.g., "llama3.2") |
| `rerankerEndpoint` | http://localhost:11434 | URL | Ollama API endpoint |
| `rerankerMaxCandidates` | 20 | 1–100 | Max docs sent to LLM |

> [!WARNING]
> Re-ranking adds **100–500ms latency** per query. Use only when precision is critical and latency budget allows.

---

## 🖥️ Server Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `port` | 7070 | HTTP server port |
| `apiKey` | — | Optional API key (empty = no auth) |
| `corsOrigins` | * | Allowed CORS origins |

```bash
# Format: port dimensions apiKey
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer" \
  -Dexec.args="7070 384 my-secret-key"
```

---

## 🌐 Cluster Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `shardCount` | 2 | 2–256 | Number of data shards |
| `replicaCount` | 1 | 1–5 | Replicas per shard |
| `heartbeatInterval` | 2s | 500ms–30s | Cluster heartbeat interval |
| `heartbeatTimeout` | 10s | 3s–120s | Node unavailability timeout |
| `queryTimeout` | 10s | 1s–60s | Per-shard query timeout |

> [!TIP]
> Rule of thumb: **100K–500K docs per shard** for optimal balance. Set `heartbeatTimeout` to at least 5× `heartbeatInterval`.

---

## 🤖 RAG Pipeline Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `maxTokens` | 512 | 1–8192 | Max tokens per chunk |
| `overlapTokens` | 50 | 0–maxTokens-1 | Overlap between chunks |
| `embeddingBatchSize` | 32 | 1–256 | Batch size for embedding generation |
| `embeddingRetries` | 3 | 0–10 | Retry count for failed batches |
| `contextTokenLimit` | 4096 | 256–131072 | Max tokens in assembled context |

---

## 🎯 Configuration Examples

### 🎯 High-Recall Setup

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(500_000)
    .withQuantization(QuantizationType.SCALAR_INT8)
    .withM(32)
    .withEfConstruction(400)
    .withEfSearch(200);
```

### 🗜️ Balanced Compression (INT4)

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(50_000_000)
    .withQuantization(QuantizationType.SCALAR_INT4)
    .withRescore(3);  // default for INT4
```

### 💾 Maximum Compression (INT2)

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(200_000_000)
    .withQuantization(QuantizationType.SCALAR_INT2)
    .withRescore(5);  // default for INT2
```

### ⚡ Low-Latency Setup

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(128)
    .withCapacity(100_000)
    .withM(12)
    .withEfConstruction(100)
    .withEfSearch(30);
```

### 🎮 GPU-Accelerated Batch Processing

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(768)
    .withCapacity(1_000_000)
    .withGpu(true)
    .withGpuMemoryBudget(2048);  // 2 GB
```

### 🤖 RAG Pipeline

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withMaxTokens(1024)
    .withOverlapTokens(100)
    .withEmbeddingBatchSize(64);
```

---

## 🔗 See Also

- [Performance Tuning](../operations/performance-tuning.md) — Benchmarks and optimization strategies
- [Architecture Overview](../architecture/overview.md) — How configuration affects system behavior
- [Distributed Mode](../architecture/distributed-mode.md) — Cluster-specific configuration
- [GPU Acceleration](../architecture/gpu-acceleration.md) — GPU setup requirements
