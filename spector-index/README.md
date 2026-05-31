# spector-index 🔢

> **Core indexing engine of Spector: HNSW, IVF, Product Quantization (PQ), and BM25.**

`spector-index` houses the algorithmic core of both keyword and semantic searches. It includes standard and quantized HNSW graphs, coarse Centroid Voronoi Partitioners (IVF), Product Quantizers, and a pure-Java high-speed BM25 postings index.

---

## 🏗️ Core Architecture & Packages

### 1. `com.spectrayan.spector.index.hnsw` 🕸️
Implements Hierarchical Navigable Small World (HNSW) graphs. Supports:
- **Standard HNSW:** Float32 exact search.
- **Quantized HNSW:** Asymmetric Distance Computation (ADC) graph traversal using low-level bit-packed INT8, INT4, and INT2 scalar quantization strategy bindings.

### 2. `com.spectrayan.spector.index.spectrum` 🌀
Home of **SpectorIndex**, our flagship adaptive shard index. It implements a multi-level coarse-routing structure:
- **Level 1 (IVF):** centoids learned via K-Means++. Routings computed in absolute coordinate space.
- **Level 2 (SpectorShard):** Each Voronoi cell is flat when small, automatically promoted to a local quantized HNSW graph once it exceeds a size threshold. Stores vectors as tight high-precision residual coordinates (`r = x - c`) quantized with 132-bit SVASQ.

### 3. `com.spectrayan.spector.index.ivf` & `pq` 🗜️
Product Quantization algorithms that divide vector dimensions into orthogonal subspaces and learn codebooks via K-Means++, enabling **32× memory compression** for billion-scale datasets.

### 4. `com.spectrayan.spector.index.text` 📄
A pure Java, concurrent BM25 keyword search index utilizing lock-free posting lists, virtual threads, and advanced term frequency saturation configurations.

---

## 🚀 Key APIs

### Creating a Quantized HNSW Index
```java
HnswParams params = new HnswParams(16, 200, 50); // M, efConstruction, efSearch
QuantizedHnswIndex index = new QuantizedHnswIndex(dimensions, capacity, params, QuantizationType.SCALAR_INT8);

index.add("doc-123", 123, vector);
ScoredResult[] results = index.search(queryVector, 10);
```

### SpectorIndex (IVF-HNSW-SVASQ) builder
```java
SpectorIndex spector = SpectorIndex.builder()
    .dimensions(384)
    .nCentroids(256)
    .nProbe(16)
    .shardThreshold(10_000)
    .similarityFunction(SimilarityFunction.COSINE)
    .build();

spector.train(trainingSample);
spector.add("doc-1", 1, vector);
```
