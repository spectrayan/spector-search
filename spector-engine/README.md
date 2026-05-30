# spector-engine ⚙️

> **The unified developer-facing facade, lifecycle manager, and configuration orchestrator for Spector.**

`spector-engine` acts as the primary developer gateway. It groups all separate indices (HNSW, IVF-PQ, BM25), off-heap FFM vector stores, GPU wrappers, and LLM re-rankers under a single, highly intuitive facade: **`SpectorEngine`**.

---

## 🏗️ Core Architecture & Roles

1. **Unified Facade (`SpectorEngine`):** Orchestrates document chunking, parallel embedding generation, postings insertion, and vector storage inside a single `ingest` call.
2. **Configuration Builder (`SpectorConfig`):** Fluent developer-facing builder that manages default configuration properties, auto-selects appropriate quantization modes, and calculates optimal rescoring scales.
3. **Training Buffer (`EngineTrainingBuffer`):** Safely caches the first set of ingested vectors to build representative training sets before automatically executing `K-Means++` and building the `IVF` / `SpectorIndex` Centroids.

---

## 🚀 Key APIs

### Starting the Engine
```java
SpectorConfig config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(50_000)
    .withQuantization(QuantizationType.SCALAR_INT8)
    .withRescore(3);

try (SpectorEngine engine = new SpectorEngine(config)) {
    // High-level Ingestion
    engine.ingest("doc-abc", "Document text content...", embedding);

    // Orchestrated Hybrid Search with re-ranking
    SearchResponse response = engine.hybridSearch("search query", queryVector, 5);
}
```
