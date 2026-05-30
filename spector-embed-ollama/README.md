# spector-embed-ollama 🤖

> **Out-of-the-box Ollama embedding integration, fallback handling, and parallel batch calling for Spector.**

`spector-embed-ollama` implements the `EmbeddingProvider` contract for local Ollama instances. It supports parallel API calls, high-throughput batching, automatic JSON escape handling, and resilient connection timeout fallbacks.

---

## 🏗️ Core Architecture & Roles

1. **`OllamaEmbeddingProvider`:** Connects to local or remote Ollama HTTP servers (e.g. `http://localhost:11434/api/embed`) using asynchronous JDK HTTP Clients.
2. **Parallel GPU Batching:** Splits large text collections into optimal GPU batches (e.g., 500 vectors) to saturate local GPU accelerators.
3. **Resiliency Fallbacks:** Manages connection pooling, HTTP request timeouts, and automatically retries failed batches to ensure ingestion pipeline safety.

---

## 🚀 Key APIs

### Configuring Ollama Provider
```java
// Connect to a local Ollama service running qwen3-embedding
EmbeddingProvider provider = new OllamaEmbeddingProvider(
    "http://localhost:11434",
    "qwen3-embedding"
);

// Single vector generation
float[] vector = provider.embed("Spector uses Panama FFM");

// Batch generation
List<String> sentences = List.of("First sentence", "Second sentence");
float[][] batchVectors = provider.embedBatch(sentences);
```
