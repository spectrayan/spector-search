# spector-embed-api 🧬

> **The pluggable Embedding Provider Service Provider Interface (SPI) contract for Spector.**

`spector-embed-api` defines the public SPI contract that allows developers to plug custom text embedding generators into the Spector ingestion and query pipelines. This ensures that Spector remains completely independent of any specific LLM provider or hosting environment.

---

## 🏗️ Core Architecture & Contract

### 1. `EmbeddingProvider`
The core SPI interface that all embedding connectors must implement:
```java
public interface EmbeddingProvider extends AutoCloseable {
    /** Generates a single float32 embedding vector for the text. */
    float[] embed(String text) throws Exception;

    /** Batch generates embeddings in parallel for a collection of texts. */
    float[][] embedBatch(List<String> texts) throws Exception;

    /** Returns the dimensionality of the generated vectors. */
    int dimensions();

    /** Returns the model identifier. */
    String modelName();
}
```

---

## 🚀 Usage

To register a custom provider, implement `EmbeddingProvider` and register it using standard Java `ServiceLoader` declarations or wire it directly to the Spector Config builder during engine startup.
