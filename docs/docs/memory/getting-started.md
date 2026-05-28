---
title: Getting Started
description: "Set up Spector Memory in 5 minutes — from Maven dependency to your first remember/recall cycle."
---

# Getting Started

Get cognitive memory running in your Java application in under 5 minutes.

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| **JDK** | 25+ | OpenJDK with Vector API incubator |
| **Maven** | 3.9+ | Build tool |
| **Ollama** | Latest | For real embeddings (optional — mock provider works for testing) |

## Maven Dependency

```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-memory</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Ollama embedding provider (optional) -->
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-embed-ollama</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## JVM Flags

Spector Memory uses the Vector API (incubator) for SIMD acceleration:

```bash
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     --enable-preview \
     -jar your-app.jar
```

!!! tip "Maven Surefire"
    These flags are already configured in the parent `pom.xml`. Tests run out of the box with `mvn test`.

---

## Minimal Example

### With Mock Embeddings (No Ollama Required)

```java
import com.spectrayan.spector.memory.*;
import com.spectrayan.spector.memory.cortex.MemorySource;

// Create a mock embedding provider for testing
EmbeddingProvider mock = text -> {
    float[] vec = new float[128];
    // Deterministic hash-based vector for reproducibility
    var rng = new java.util.Random(text.hashCode());
    for (int i = 0; i < 128; i++) vec[i] = rng.nextFloat() - 0.5f;
    return new EmbeddingResult(vec, text.split("\\s+").length, "mock");
};

try (SpectorMemory memory = SpectorMemory.builder()
        .dimensions(128)
        .embeddingProvider(mock)
        .build()) {
    
    // Remember
    memory.remember("fact-1", 
        "The user prefers dark mode in all editors.",
        MemoryType.EPISODIC, MemorySource.USER_STATED,
        "preferences", "ui").get();
    
    // Recall
    List<CognitiveResult> results = memory.recall("dark theme settings",
        RecallOptions.builder().topK(5).build());
    
    results.forEach(r -> 
        System.out.printf("%.4f [%s] %s%n", r.score(), r.memoryType(), r.text()));
}
```

### With Real Ollama Embeddings

```java
import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;

// Pull the model first: ollama pull qwen3-embedding
var embedder = OllamaEmbeddingProvider.create("qwen3-embedding");

try (SpectorMemory memory = SpectorMemory.builder()
        .dimensions(embedder.dimensions())  // Auto-detect: 4096 for qwen3-embedding
        .embeddingProvider(embedder)
        .workingCapacity(100)
        .episodicPartitionCapacity(10_000)
        .semanticCapacity(5_000)
        .proceduralCapacity(500)
        .build()) {
    
    // Ingest diverse memories
    memory.remember("err-db", 
        "Database connection pool exhausted — 50 active, 0 idle connections.",
        MemoryType.EPISODIC, MemorySource.OBSERVED,
        "error", "database").get();
    
    memory.remember("rule-retry",
        "Always implement exponential backoff for database retries.",
        MemoryType.PROCEDURAL, MemorySource.PROCEDURAL,
        "database", "retry").get();
    
    // Semantic recall with synaptic tag filtering
    List<CognitiveResult> results = memory.recall("database connection error",
        RecallOptions.builder()
            .topK(5)
            .synapticFilter("database")        // Only memories tagged "database"
            .minImportance(0.2f)               // Skip trivial memories
            .build());
}
```

---

## Core Operations

### Remember (Ingestion)

```java
// Async — returns CompletableFuture
CompletableFuture<Void> future = memory.remember(
    "unique-id",                    // Unique memory identifier
    "The text content to remember", // Raw text (will be auto-embedded)
    MemoryType.EPISODIC,            // Cognitive tier
    MemorySource.USER_STATED,       // Provenance
    "tag1", "tag2", "tag3"          // Synaptic tags (Bloom filter encoded)
);
future.get(); // Block if needed
```

### Recall (Retrieval)

```java
List<CognitiveResult> results = memory.recall("query text",
    RecallOptions.builder()
        .topK(10)                              // Max results
        .synapticFilter("java", "debugging")   // Bloom filter pre-screen
        .minImportance(0.3f)                   // Importance threshold
        .memoryTypes(MemoryType.EPISODIC,      // Tier filter
                     MemoryType.SEMANTIC)
        .minValence((byte) -50)                // Emotional range
        .maxValence((byte) 50)
        .alpha(0.6f)                           // Similarity weight
        .beta(0.4f)                            // Importance × decay weight
        .build());
```

### Forget & Suppress

```java
// Permanent: tombstone the memory (excluded from all future scans)
memory.forget("memory-id");

// Temporary: suppress from recall (can be un-suppressed later)
memory.suppress("memory-id", "Not relevant to current task");
```

### Introspect

```java
// Memory health statistics
int total = memory.totalMemories();
var stats = memory.introspect();
```

---

## Claude Desktop / MCP Integration

Add cognitive memory to your AI agent via the built-in MCP server. Enable memory in your `spector.yml`:

```yaml
spector:
  engine:
    dimensions: 4096
  embedding:
    model: qwen3-embedding
    base-url: http://localhost:11434
  memory:
    enabled: true
    persistence-path: .spector-memory
```

Then configure your agent:

```json
{
  "mcpServers": {
    "spector-search": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview",
        "-jar", "/path/to/spector.jar",
        "--config", "/path/to/spector.yml"
      ]
    }
  }
}
```

With `memory.enabled: true`, the MCP server registers all 13 tools (6 search + 7 cognitive memory).

---

## Next Steps

- :material-brain: [**System Architecture**](architecture.md) — understand the full package hierarchy
- :material-lightning-bolt: [**6-Phase Scoring Pipeline**](scoring-pipeline.md) — how recall actually works under the hood
- :material-head-cog: [**Biological Systems**](cortex.md) — explore each brain region
- :material-speedometer: [**Performance**](performance.md) — benchmarks and optimization techniques
