# spector-client 🔌

> **High-performance Java SDK client for remote Spector servers.**

`spector-client` implements a type-safe, developer-friendly Java SDK for interacting with remote `SpectorNode` nodes. It handles HTTP request builders, JSON serialization/deserialization, connection pooling, and resilient API call fallbacks automatically.

---

## 🚀 Key APIs

### Creating Remote Client Context
```java
SpectorClientConfig config = SpectorClientConfig.builder()
    .endpoint("http://localhost:7070")
    .apiKey("my-highly-secure-api-key")
    .timeout(Duration.ofSeconds(10))
    .build();

try (SpectorClient client = new SpectorClient(config)) {
    // Ingest remote document
    client.ingest("doc-1", "Semantic Java SDK Client", embedding);
    
    // Execute search request
    SearchResponse response = client.search("java sdk client", queryVector, 10);
    
    for (ScoredResult r : response.results()) {
        System.out.println(r.id() + " -> " + r.score());
    }
}
```
