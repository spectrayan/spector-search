# 🚀 Getting Started

> **Go from zero to your first search result in under 5 minutes.** This guide walks you through building Spector Search from source, starting the server, ingesting documents, and running your first hybrid search.

---

## 📋 Prerequisites

| Tool | Version | How to Check |
|------|---------|-------------|
| ☕ JDK | 25+ | `java -version` |
| 📦 Maven | 3.9+ | `mvn --version` |
| 🔧 Git | 2.40+ | `git --version` |

> [!IMPORTANT]
> Spector Search requires **JDK 25 or later** with the Vector API incubator module. [OpenJDK builds](https://jdk.java.net/) include this by default.

---

## 🏗️ Clone and Build

```bash
# Clone the repository
git clone https://github.com/spectrayan/spector-search.git
cd spector-search

# Build all modules (includes 316+ tests)
mvn clean test

# Build without tests (faster)
mvn clean package -DskipTests
```

> [!TIP]
> The full test suite runs 316+ tests across all modules. Expect ~2 minutes on a modern machine.

---

## 🔬 Verify SIMD Support

Confirm your hardware's SIMD acceleration level:

```bash
java --add-modules jdk.incubator.vector -cp spector-core/target/classes \
  com.spectrayan.spector.core.SimdCapability
```

Expected output (varies by hardware):
```
SIMD Species: S_256_BIT (AVX2, 8 float lanes)
```

---

## 🖥️ Start the Server

```bash
# Start on default port 7070 with 384 dimensions
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer"

# Start with custom port, dimensions, and API key
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer" \
  -Dexec.args="7070 384 my-secret-key"
```

Verify it's running:

```bash
curl http://localhost:7070/health
```

```json
{"status": "UP"}
```

> [!NOTE]
> The server starts on virtual threads — it can handle thousands of concurrent requests out of the box with no thread pool configuration needed.

---

## 📄 Ingest Your First Document

```bash
curl -X POST http://localhost:7070/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-1",
    "title": "Introduction to Vector Search",
    "content": "Vector search finds similar items by comparing their mathematical representations called embeddings.",
    "vector": [0.12, 0.45, 0.78, 0.23, 0.91, 0.34, 0.67, 0.55, 0.11, 0.89]
  }'
```

```json
{"id": "doc-1", "status": "indexed"}
```

### 🤖 Ingest with Auto-Embedding

If you have Ollama running with an embedding model:

```bash
curl -X POST http://localhost:7070/api/v1/ingest/auto \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-2",
    "title": "HNSW Algorithm",
    "content": "Hierarchical Navigable Small World graphs enable fast approximate nearest neighbor search."
  }'
```

### 📦 Bulk Ingest

```bash
curl -X POST http://localhost:7070/api/v1/ingest/bulk \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {"id": "d1", "content": "BM25 keyword scoring uses term frequency and document length.", "vector": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]},
      {"id": "d2", "content": "Reciprocal Rank Fusion combines multiple ranked lists.", "vector": [0.5, 0.4, 0.3, 0.2, 0.1, 0.9, 0.8, 0.7, 0.6, 0.5]}
    ]
  }'
```

---

## 🔍 Run Your First Search

### 🧬 Hybrid Search (keyword + vector)

```bash
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "text": "nearest neighbor search",
    "vector": [0.15, 0.42, 0.73, 0.28, 0.88, 0.31, 0.62, 0.51, 0.14, 0.85],
    "topK": 5
  }'
```

```json
{
  "results": [
    {
      "id": "doc-1",
      "score": 0.9234,
      "title": "Introduction to Vector Search",
      "content": "Vector search finds similar items..."
    }
  ],
  "searchMode": "HYBRID",
  "latencyMs": 0.31
}
```

### 📝 Keyword-Only Search

```bash
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"text": "BM25 scoring", "topK": 10}'
```

### 🧠 Vector-Only Search

```bash
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"vector": [0.15, 0.42, 0.73, 0.28, 0.88, 0.31, 0.62, 0.51, 0.14, 0.85], "topK": 10}'
```

---

## 📊 Check Engine Status

```bash
curl http://localhost:7070/api/v1/status
```

```json
{
  "status": "RUNNING",
  "simd": "AVX2 (256-bit, 8 lanes)",
  "gpuAvailable": false,
  "rerankerEnabled": false,
  "documentCount": 3,
  "dimensions": 384
}
```

---

## 💻 Use as an Embedded Library

No server needed — use Spector Search directly in your Java application:

```java
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.engine.SpectorConfig;

var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(100_000);

try (var engine = new SpectorEngine(config)) {
    // Ingest
    engine.ingest("doc-1", "Hello world", new float[]{0.1f, 0.2f, ...});

    // Search
    var results = engine.hybridSearch("hello", queryVector, 10);

    for (var result : results.results()) {
        System.out.printf("%s → %.4f%n", result.id(), result.score());
    }
}
```

> [!TIP]
> Embedded mode has **zero network overhead** — perfect for microservices, desktop apps, and edge deployments.

---

## 🎉 What You've Accomplished

In just a few minutes, you've:
- ✅ Built Spector Search from source
- ✅ Verified SIMD hardware acceleration
- ✅ Started a search server
- ✅ Ingested documents
- ✅ Run hybrid search queries

---

## 🚀 Next Steps

| What to explore | Page |
|----------------|------|
| Full API documentation | [REST API Reference](../api-reference/rest-endpoints.md) |
| Type-safe Java client | [Java SDK Guide](../sdk-usage/java-client.md) |
| Tune for your workload | [Configuration Guide](../configuration/parameters.md) |
| Command-line management | [CLI Reference](../cli-reference/spectorctl.md) |
| Understand the internals | [Architecture Overview](../architecture/overview.md) |
| Spring AI integration | [Spring AI Integration](../sdk-usage/spring-ai.md) |
