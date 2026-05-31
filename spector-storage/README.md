# spector-storage 💾

> **Zero-copy, off-heap vector storage built on Panama Foreign Function & Memory (FFM) API.**

`spector-storage` implements high-speed vector allocation, memory segment layouts, and memory-mapped persistence layers. By using Project Panama's off-heap `MemorySegment` capabilities, it completely avoids JVM garbage collection pressure, even when storing millions of high-dimensional embeddings in memory.

---

## 🏗️ Core Architecture & Roles

1. **Off-Heap Storage (`MemorySegmentStore`):** Stores uncompressed float32 vectors directly in contiguous off-heap virtual memory segments.
2. **Memory-Mapped Persistence (`MmapStore`):** Uses OS-level page cache via `mmap` to persist vector segments directly to disk, allowing files to survive JVM restarts and load instantaneously.
3. **Quantized Vector Store (`QuantizedVectorStore`):** Compresses vectors using low-level layouts. Integrates directly with SVASQ (INT8, INT4, INT2) formats, storing compressed coordinates and scaling metadata in space-efficient off-heap bit-packed segments.

---

## 🚀 Key APIs

### Allocating Off-Heap Store
```java
int dimensions = 384;
int capacity = 100_000;

try (VectorStore store = new MemorySegmentStore(dimensions, capacity)) {
    float[] vector = new float[384];
    
    // Store vector at a specific index
    store.put(42, vector);
    
    // Retrieve vector without heap allocation
    float[] retrieved = store.get(42);
}
```

### Memory-Mapped Vector Persistence
```java
Path filePath = Path.of("vectors.mmap");
try (VectorStore mmapStore = new MmapStore(filePath, dimensions, capacity)) {
    mmapStore.put(99, queryVector);
} // Flushed and saved instantly
```
