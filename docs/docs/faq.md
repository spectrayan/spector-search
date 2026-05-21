# ❓ FAQ

> **Quick answers to the most common questions about Spector Search.** Can't find what you're looking for? Check [GitHub Discussions](https://github.com/spectrayan/spector-search/discussions) or the specific wiki pages linked throughout.

---

## 🌟 General

### What Java version do I need?

**JDK 25 or later.** Spector Search uses the Java Vector API (incubator module) for SIMD acceleration and Panama FFM for off-heap memory. [OpenJDK builds](https://jdk.java.net/) include these by default.

---

### Does it work without a GPU?

**Yes, completely.** GPU is optional. Without a GPU, Spector Search uses CPU SIMD acceleration (AVX2/AVX-512/NEON) which delivers sub-millisecond search at 100K documents. GPU helps primarily for high-concurrency batch workloads.

> [!TIP]
> See [GPU Acceleration](architecture/gpu-acceleration.md) for details on when GPU adds value (spoiler: batch sizes > 32).

---

### Can I use it as an embedded library?

**Absolutely!** Spector Search runs in two modes:

| Mode | Description | Overhead |
|------|-------------|----------|
| **Embedded** | Add JAR to classpath, create `SpectorEngine` | Zero network overhead |
| **Server** | REST API with auth, CORS, metrics | HTTP overhead |

```java
try (var engine = new SpectorEngine(SpectorConfig.DEFAULT.withDimensions(384))) {
    engine.ingest("id", "content", vector);
    var results = engine.hybridSearch("query", queryVector, 10);
}
```

---

### What about persistence? Do I lose data on restart?

**No!** Spector Search supports persistence through memory-mapped files. The HNSW index uses a page-aligned binary format that loads instantly via `mmap` — no deserialization needed. Vector data survives restarts.

---

### How does it compare to Elasticsearch?

| Aspect | ⚡ Spector Search | Elasticsearch |
|--------|---------------|--------------|
| Vector search latency | **0.05 ms** | 2–10 ms |
| Hybrid search latency | **0.47 ms** | 10–30 ms |
| Deployment | Embedded JAR or server | Cluster only |
| Dependencies | **Zero** (JDK only) | JVM + heavy stack |
| GPU support | ✅ CUDA | ❌ |
| IVF-PQ compression | ✅ 32× | ❌ |

> Elasticsearch excels at distributed full-text search with a mature query language. Spector excels at raw performance, embedded use, and modern JVM features.

---

### Does it support filtering/metadata queries?

**Yes.** The Spring AI integration supports filter expressions:

```java
vectorStore.similaritySearch(
    SearchRequest.query("search algorithms")
        .withFilterExpression("category == 'indexing' && version > 2")
);
```

---

### What embedding models work with Spector Search?

Any model that produces float32 vectors. Set `dimensions` to match:

| Model | Dimensions | Provider |
|-------|-----------|----------|
| all-MiniLM-L6-v2 | 384 | Sentence Transformers / Ollama |
| e5-base-v2 | 768 | Sentence Transformers |
| text-embedding-ada-002 | 1536 | OpenAI |
| nomic-embed-text | 768 | Ollama |
| mxbai-embed-large | 1024 | Ollama |

> [!NOTE]
> Spector Search includes an Ollama embedding provider out of the box. Implement the `EmbeddingProvider` SPI for any other source.

---

## 🔧 Technical

### What similarity functions are supported?

| Function | Best For |
|----------|----------|
| **COSINE** (default) | Normalized embeddings (most models) |
| **DOT_PRODUCT** | Unnormalized embeddings, magnitude matters |
| **EUCLIDEAN** | Spatial/geometric data |

---

### What's the maximum dataset size?

| Mode | Scale |
|------|-------|
| Single node | Up to 10 million documents |
| IVF-PQ mode | Billions of vectors (32× compression) |
| Distributed mode | Scale horizontally (2–256 shards) |

---

### How does the LLM re-ranking work?

```mermaid
flowchart LR
    A["🔍 Search<br/>Top-N candidates"] --> B["🤖 LLM (Ollama)<br/>Listwise scoring"]
    B --> C["✨ Re-ranked<br/>Top-K results"]
```

1. Vector/hybrid search retrieves top-N candidates (default: 20)
2. Candidates sent to Ollama for listwise relevance scoring
3. LLM reorders based on semantic relevance
4. Final top-K results reflect LLM judgment

> [!WARNING]
> Adds 100–500ms latency but significantly improves precision for ambiguous queries.

---

### What are virtual threads and why do they matter?

Virtual threads (Project Loom) are lightweight threads that don't map 1:1 to OS threads:

- ✅ Handle millions of concurrent requests without pool tuning
- ✅ No `synchronized` blocks that pin platform threads
- ✅ Near-zero scheduling overhead
- ✅ Linear scaling (4.5× at 16 threads measured)

---

### How does zero-copy storage work?

Vectors are stored in memory-mapped files using Panama's `MemorySegment`:
- OS maps file directly into process address space
- SIMD kernels read vectors without copying to Java heap
- Zero garbage collection pressure
- Instant startup (no deserialization)
- Supports datasets larger than available RAM

---

### What's the difference between HNSW and IVF-PQ?

| Aspect | 🌐 HNSW | 🗜️ IVF-PQ |
|--------|------|--------|
| Speed | Fastest (0.05ms) | Fast (nprobe-dependent) |
| Memory | Full vectors (1.5KB/vec @ 384-dim) | 32× compressed (48 bytes/vec) |
| Recall | High (configurable) | Moderate (nprobe-dependent) |
| Scale | Up to millions | Up to billions |
| Use case | Default for most workloads | Memory-constrained, billion-scale |

---

### Can I run benchmarks in CI?

**Yes!** JSON output + baseline regression detection:

```bash
mvn -pl spector-bench exec:java -Dexec.args="-rf json -rff results.json"
```

---

## ⚙️ Operations

### What ports does Spector Search use?

| Port | Protocol | Purpose |
|------|----------|---------|
| 7070 | HTTP | REST API (configurable) |
| 9090 | gRPC | Cluster communication (distributed mode) |

---

### How do I monitor Spector Search?

```bash
curl http://localhost:7070/health          # Health check
curl http://localhost:7070/api/v1/status    # Engine status
curl http://localhost:7070/api/v1/metrics   # Request metrics
```

---

### What JVM arguments should I use in production?

```bash
java \
  --add-modules jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseZGC -XX:+ZGenerational \
  -Xmx4g -Xms4g \
  -jar spector-server.jar
```

---

### How do I upgrade without downtime?

**Distributed mode:**
1. Drain one node (stop routing requests)
2. Upgrade the node binary
3. Restart and wait for replica sync
4. Repeat for each node

**Embedded mode:** Standard application deployment with new Spector version.

---

### Is there authentication?

**Yes.** Set an API key at server startup:

```bash
mvn exec:java -pl spector-server \
  -Dexec.args="7070 384 my-secret-key"
```

Clients include `X-API-Key: my-secret-key` in requests. Without a key configured, all requests are allowed.

---

## 🔗 See Also

- [Getting Started](getting-started/quickstart.md) — Quick start guide
- [What is Spector Search](about.md) — Product overview
- [Configuration Guide](configuration/parameters.md) — All parameters
- [Performance Tuning](operations/performance-tuning.md) — Optimization strategies
