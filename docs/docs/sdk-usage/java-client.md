# ☕ Java SDK Guide

> **Type-safe, thread-safe Java access to Spector Search — as a remote client or embedded engine.** Whether you're connecting to a server or embedding search directly in your application, this guide covers everything you need.

---

## 📦 Installation

**Remote client** (connects to a running server):

```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-client</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**Embedded engine** (in-process, zero network overhead):

```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-engine</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

> [!TIP]
> Choose **embedded** for maximum performance (zero latency overhead). Choose **client** when you want a shared server across multiple services.

---

## 🌐 Client SDK (Remote Server)

### 🔧 Creating a Client

```java
import com.spectrayan.spector.client.SpectorClient;

SpectorClient client = SpectorClient.builder()
    .host("localhost")
    .port(7070)
    .apiKey("my-secret-key")       // optional
    .connectTimeout(Duration.ofSeconds(10))
    .requestTimeout(Duration.ofSeconds(30))
    .maxConnections(10)
    .build();
```

**Configuration Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `host` | localhost | Server hostname |
| `port` | 7070 | Server port |
| `apiKey` | — | API key for authentication |
| `connectTimeout` | 10s | Connection timeout |
| `requestTimeout` | 30s | Per-request timeout |
| `maxConnections` | 10 | HTTP connection pool size |

> [!NOTE]
> `SpectorClient` is fully **thread-safe**. It uses Java's `HttpClient` with internal connection pooling. Share a single instance across all threads.

---

### 📥 Ingesting Documents

```java
// Single document
IngestResponse response = client.ingest(IngestRequest.builder()
    .id("doc-1")
    .title("Java Vector API")
    .content("SIMD-accelerated search engine built on modern JVM")
    .vector(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f})
    .build());

System.out.println("Indexed: " + response.id());
```

```java
// Bulk ingest
List<IngestRequest> documents = List.of(
    IngestRequest.builder().id("d1").content("first doc").vector(vec1).build(),
    IngestRequest.builder().id("d2").content("second doc").vector(vec2).build(),
    IngestRequest.builder().id("d3").content("third doc").vector(vec3).build()
);

IngestResponse bulkResponse = client.bulkIngest(documents);
```

---

### 🔍 Searching

```java
// Keyword search
SearchResponse results = client.search(SearchRequest.builder()
    .text("vector search engine")
    .topK(10)
    .build());

// Vector search
SearchResponse results = client.search(SearchRequest.builder()
    .vector(queryEmbedding)
    .topK(10)
    .build());

// Hybrid search (both text and vector)
SearchResponse results = client.search(SearchRequest.builder()
    .text("search engine")
    .vector(queryEmbedding)
    .topK(10)
    .build());

// Process results
for (SearchResponse.Result result : results.results()) {
    System.out.printf("%s (%.4f): %s%n",
        result.id(), result.score(), result.content());
}
```

---

### 🗑️ Deleting Documents

```java
client.delete("doc-1");
```

### 📊 Status and Metrics

```java
StatusResponse status = client.status();
System.out.println("Documents: " + status.documentCount());
System.out.println("SIMD: " + status.simd());

MetricsResponse metrics = client.metrics();
System.out.println("QPS: " + metrics.queriesPerSecond());
```

---

### ⚠️ Error Handling

```java
try {
    client.search(request);
} catch (SpectorApiException e) {
    // HTTP 4xx/5xx from server
    System.err.println("HTTP " + e.statusCode() + ": " + e.message());
} catch (SpectorConnectionException e) {
    // Server unreachable
    System.err.println("Cannot connect to " + e.endpoint());
} catch (SpectorTimeoutException e) {
    // Request timed out
    System.err.println("Timeout after " + e.timeout());
}
```

### ♻️ Resource Management

The client implements `AutoCloseable`:

```java
try (SpectorClient client = SpectorClient.builder().build()) {
    // Use client...
} // Connections released automatically
```

---

## ⚡ SpectorEngine (Embedded Usage)

For applications that want in-process search without network overhead:

### 🔧 Creating an Engine

```java
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.engine.SpectorConfig;

var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(100_000)
    .withSimilarityFunction(SimilarityFunction.COSINE)
    .withGpu(true)                                           // optional GPU
    .withReranker("http://localhost:11434", "llama3.2", 20); // optional LLM

try (var engine = new SpectorEngine(config)) {
    // Engine is ready — sub-millisecond search, zero network overhead
}
```

### 📥 Ingesting

```java
// With pre-computed vector
engine.ingest("doc-1", "Document content here", embedding);
// The engine handles BM25 indexing, HNSW insertion, and storage automatically
```

### 🔍 Searching

```java
// Hybrid search (keyword + vector)
SearchResponse response = engine.hybridSearch("search query", queryVector, 10);

// Keyword-only
SearchResponse response = engine.keywordSearch("exact phrase", 10);

// Vector-only
SearchResponse response = engine.vectorSearch(queryVector, 10);

// Process results
for (ScoredResult result : response.results()) {
    System.out.printf("%s → %.4f%n", result.id(), result.score());
}
```

### 🗑️ Deleting

```java
engine.delete("doc-1");
```

---

## 🎯 Complete Example

```java
import com.spectrayan.spector.client.SpectorClient;
import com.spectrayan.spector.client.model.*;

public class SpectorExample {
    public static void main(String[] args) throws Exception {
        try (SpectorClient client = SpectorClient.builder()
                .host("localhost")
                .port(7070)
                .build()) {

            // Ingest documents
            client.ingest(IngestRequest.builder()
                .id("java-1")
                .title("Virtual Threads")
                .content("Java virtual threads enable millions of concurrent tasks")
                .vector(new float[]{0.9f, 0.1f, 0.3f, 0.7f, 0.5f})
                .build());

            client.ingest(IngestRequest.builder()
                .id("java-2")
                .title("Vector API")
                .content("The Vector API provides SIMD acceleration for math operations")
                .vector(new float[]{0.2f, 0.8f, 0.4f, 0.1f, 0.6f})
                .build());

            // Search
            SearchResponse results = client.search(SearchRequest.builder()
                .text("SIMD acceleration")
                .topK(5)
                .build());

            System.out.println("Results:");
            for (var r : results.results()) {
                System.out.printf("  %s (%.4f): %s%n", r.id(), r.score(), r.title());
            }

            // Cleanup
            client.delete("java-1");
            client.delete("java-2");
        }
    }
}
```

---

## 🔗 See Also

- [REST API Reference](../api-reference/rest-endpoints.md) — Underlying API endpoints
- [Spring AI Integration](spring-ai.md) — Spring AI VectorStore adapter
- [Configuration Guide](../configuration/parameters.md) — All engine parameters
- [Getting Started](../getting-started/quickstart.md) — Quick start guide
