# Configuration Parameters

Spector Search is configured via `SpectorConfig`. All parameters have sensible defaults.

## Core Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `dimensions` | 384 | 1–2048 | Vector dimensionality |
| `capacity` | 100,000 | 1–10M | Maximum document count |
| `similarityFunction` | COSINE | COSINE, DOT_PRODUCT, EUCLIDEAN | Distance metric |

## HNSW Index Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `M` | 16 | 4–64 | Max connections per node per layer |
| `efConstruction` | 200 | 16–800 | Construction beam width (higher = better recall, slower build) |
| `efSearch` | 50 | 10–500 | Search beam width (higher = better recall, slower query) |

## BM25 Parameters

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `k1` | 1.2 | 0.0–3.0 | Term frequency saturation |
| `b` | 0.75 | 0.0–1.0 | Document length normalization |

## Hybrid Search

| Parameter | Default | Description |
|-----------|---------|-------------|
| `RRF k` | 60 | Reciprocal Rank Fusion constant |

## GPU Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `gpuEnabled` | false | Enable CUDA GPU acceleration |
| `gpuMemoryBudget` | 256 MB | Maximum GPU memory allocation |

> **Note:** For INT4/INT2 quantization, GPU acceleration requires vector dimensions to be a multiple of 32. Non-aligned dimensions automatically fall back to CPU/SIMD.

## Quantization Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `quantization` | NONE | NONE, SCALAR_INT8, SCALAR_INT4, SCALAR_INT2 | Scalar quantization type |
| `oversamplingFactor` | auto | 1–20 | Rescore oversampling factor (auto: INT8→1, INT4→3, INT2→5) |

### Quantization Types

| Type | Compression | Recall | Calibration | Best For |
|------|-------------|--------|-------------|----------|
| SCALAR_INT8 | 4× | 95–99% | Linear (min/max) | High-recall, moderate scale |
| SCALAR_INT4 | 8× | 85–95% | Non-uniform (quantile) | Balanced compression/recall |
| SCALAR_INT2 | 16× | 75–90% | Non-uniform (quantile) | Memory-constrained, large datasets |

### Rescore Strategy

When `oversamplingFactor > 1`, Spector retrieves `oversamplingFactor × k` candidates using fast quantized distance, then rescores with exact float32 distances to return the true top-K:

| Quantization | Default Oversampling | Effect |
|-------------|---------------------|--------|
| INT8 | 1 (no rescore) | Already near-lossless |
| INT4 | 3 | Recovers recall to 85–95% |
| INT2 | 5 | Compensates for aggressive quantization |

Set `oversamplingFactor` to 1 to disable rescoring (faster, lower recall).

## Reranker Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `rerankerEnabled` | false | Enable LLM re-ranking via Ollama |
| `rerankerModel` | — | Ollama model name (e.g., "llama3.2") |
| `rerankerEndpoint` | http://localhost:11434 | Ollama API endpoint |
| `rerankerMaxCandidates` | 20 | Max docs sent to LLM for re-ranking |

## Server Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `port` | 7070 | HTTP server port |
| `apiKey` | — | Optional API key for authentication |

## Cluster Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `shardCount` | 2 | 2–256 | Number of data shards |
| `replicaCount` | 1 | 1–5 | Replicas per shard |
| `heartbeatInterval` | 2s | 500ms–30s | Cluster heartbeat interval |
| `heartbeatTimeout` | 10s | 3s–120s | Node unavailability timeout |

## RAG Pipeline Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `maxTokens` | 512 | 1–8192 | Max tokens per chunk |
| `overlapTokens` | 50 | 0–maxTokens-1 | Overlap between chunks |
| `embeddingBatchSize` | 32 | 1–256 | Embedding batch size |
| `embeddingRetries` | 3 | 0–10 | Retry count for failed batches |

## Example Configuration

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withCapacity(100_000)
    .withQuantization(QuantizationType.SCALAR_INT4)  // 8× compression
    .withRescore(3)                                   // 3× oversampling for recall
    .withGpu(true)
    .withReranker("http://localhost:11434", "llama3.2", 20);

try (var engine = new SpectorEngine(config)) {
    // Use engine...
}
```
