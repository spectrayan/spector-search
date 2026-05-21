# 🎮 GPU Acceleration

> **Unlock massive parallel throughput with optional CUDA GPU acceleration.** Spector Search loads GPU kernels via Panama FFM (Foreign Function & Memory), maintaining the zero-JNI philosophy. GPU shines for batch workloads — single queries are already sub-millisecond on CPU SIMD.

---

## 🎯 When to Use GPU

```mermaid
graph TD
    Q["How many concurrent queries?"] --> Single["Single query<br/>Low concurrency"]
    Q --> Batch["Batch queries<br/>High concurrency"]
    
    Single --> CPU["✅ CPU SIMD<br/>0.05ms, zero overhead"]
    Batch --> GPU["✅ GPU CUDA<br/>14.6× speedup at 1024 batch"]
    
    style CPU fill:#d4edda
    style GPU fill:#d4edda
```

| Scenario | Recommendation |
|----------|---------------|
| ✅ Batch search (multiple queries at once) | GPU |
| ✅ Large collections (>100K vectors) | GPU |
| ✅ High concurrency (many simultaneous users) | GPU |
| ✅ Brute-force similarity over IVF partitions | GPU |
| ⚡ Single queries | CPU SIMD |
| ⚡ Small datasets (<10K vectors) | CPU SIMD |
| ⚡ Ultra-low latency (<0.1ms) | CPU SIMD |

---

## 📋 Requirements

### Hardware

- NVIDIA GPU with Compute Capability ≥ 7.0 (Volta or newer)
- Recommended: RTX 3060+ or A100/H100 for production workloads

### Software

| Component | Version | Notes |
|-----------|---------|-------|
| CUDA Toolkit | 12.x | Runtime libraries required |
| NVIDIA Driver | 525+ | Must match CUDA version |
| JDK | 25+ | With Panama FFM support |

### 🐧 Installation (Linux)

```bash
# Install CUDA toolkit
wget https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/x86_64/cuda-keyring_1.1-1_all.deb
sudo dpkg -i cuda-keyring_1.1-1_all.deb
sudo apt update
sudo apt install cuda-toolkit-12-4

# Verify
nvidia-smi
nvcc --version
```

### ✅ Verify Spector GPU Detection

```bash
curl http://localhost:7070/api/v1/status
```
```json
{
  "gpuAvailable": true,
  "gpuInfo": "NVIDIA RTX 4090, 24GB, CUDA 12.4"
}
```

---

## ⚙️ Configuration

```java
var config = SpectorConfig.DEFAULT
    .withDimensions(384)
    .withGpu(true)
    .withGpuMemoryBudget(2048);  // 2 GB
```

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `gpuEnabled` | false | — | Enable CUDA acceleration |
| `gpuMemoryBudget` | 256 MB | 256 MB – GPU max | Maximum device memory |
| `gpuBatchWindow` | 10 ms | 1–100 ms | Batching window for query collection |
| `gpuMaxBatchSize` | 1024 | 1–1024 | Max queries per kernel launch |

!!! tip
    Set `gpuMemoryBudget` to ~70% of available GPU memory to leave room for other processes.

---

## 🔬 GPU Kernels

### Dot Product Kernel

Computes dot-product similarity between a query vector and a batch of document vectors.

| Property | Value |
|----------|-------|
| Input | query (float32[D]) + database (float32[N × D]) |
| Output | similarity scores (float32[N]) |
| Dimensions | Multiples of 32, range 32–2048 |
| Batch size | 1–1,000,000 vectors per invocation |
| Tolerance | ≤1e-5 absolute error vs CPU SIMD |

### Cosine Similarity Kernel

Computes cosine similarity with cached norm computation.

| Optimization | Benefit |
|-------------|---------|
| Pre-computes norms | Cached across queries |
| Detects pre-normalized vectors | Skips norm computation |
| Falls back to dot product | For normalized inputs |
| Tolerance | ≤1e-6 vs CPU SIMD |

### ⏱️ Batch GPU Search

```mermaid
sequenceDiagram
    participant Q1 as Query A (t=0ms)
    participant Q2 as Query B (t=3ms)
    participant Q3 as Query C (t=7ms)
    participant GPU as 🎮 GPU Kernel

    Note over Q1,GPU: Batch window = 10ms
    Q1->>GPU: Queued
    Q2->>GPU: Queued
    Q3->>GPU: Queued
    Note over GPU: t=10ms: Window closes
    GPU->>GPU: Single kernel for [A, B, C]
    GPU-->>Q1: Top-K results for A
    GPU-->>Q2: Top-K results for B
    GPU-->>Q3: Top-K results for C
```

**Properties:**
- Each query receives its own independent top-K results
- Individual query errors don't fail the batch
- Achieves ≥2× throughput vs sequential for batch sizes >4
- Large batches are automatically partitioned to fit GPU memory

---

## 💾 Memory Management

The `GpuMemoryManager` handles device memory via Panama FFM:

```java
// Allocation tied to Arena lifecycle
try (Arena arena = Arena.ofConfined()) {
    MemorySegment deviceMem = gpuMemoryManager.allocateDevice(sizeBytes, arena);
    // Use device memory...
} // Automatically freed when arena closes
```

**Key behaviors:**
- ✅ Allocations are Arena-scoped with explicit lifecycle
- ✅ Pinned host memory for efficient host↔device transfers
- ✅ Budget enforcement prevents over-allocation
- ✅ Device memory released within 100ms of Arena close
- ✅ Metrics available via monitoring API

---

## 🔄 Fallback Behavior

```mermaid
graph TD
    A["GPU Kernel Call"] --> B{"GPU available?"}
    B -->|No| C["⚡ CPU SIMD kernel<br/>(same interface)"]
    B -->|Yes| D{"Kernel execution OK?"}
    D -->|Error| E["Release device memory"]
    E --> C
    D -->|Success| F["✅ Return GPU results"]
```

!!! note
    **No code changes required.** The same method signature returns results regardless of whether GPU or CPU executed the computation. Fallback is automatic and transparent.

**Fallback triggers:**
- GPU not detected at startup
- CUDA driver not installed
- Insufficient GPU memory
- CUDA kernel execution error
- GPU memory budget exceeded

---

## 📊 Performance Characteristics

### Single Query (CPU wins)

| Method | 100K vectors, 384-dim |
|--------|----------------------|
| ⚡ CPU SIMD (AVX2) | ~0.05 ms |
| 🎮 GPU (kernel launch overhead) | ~0.5–1 ms |

### Batch Queries (GPU shines)

| Batch Size | CPU SIMD | GPU | GPU Speedup |
|-----------|----------|-----|-------------|
| 1 | 0.05 ms | 0.5 ms | 0.1× (CPU wins) |
| 8 | 0.4 ms | 0.6 ms | 0.7× |
| 32 | 1.6 ms | 0.8 ms | **2×** |
| 128 | 6.4 ms | 1.2 ms | **5.3×** |
| 512 | 25.6 ms | 2.1 ms | **12.2×** |
| 1024 | 51.2 ms | 3.5 ms | **14.6×** |

!!! important
    Results at 384 dimensions, 100K document vectors, RTX 4090. GPU breakeven point is around batch size 16–32.

---

## 🔧 Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| `gpuAvailable: false` | CUDA not installed | Install CUDA toolkit, verify `nvidia-smi` |
| Slow GPU queries | Small batch sizes | Increase `gpuBatchWindow` or disable GPU |
| Out of GPU memory | Budget too low | Increase `gpuMemoryBudget` |
| CPU fallback always used | Native access not enabled | Add `--enable-native-access=ALL-UNNAMED` |

### JVM Arguments for GPU

```bash
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -jar spector-server.jar
```

---

## 🔗 See Also

- [Core Concepts](core-concepts.md) — SIMD kernels that GPU extends
- [Performance Tuning](../operations/performance-tuning.md) — When to use GPU vs CPU
- [Configuration Guide](../configuration/parameters.md) — GPU parameters
- [Architecture Overview](overview.md) — Where GPU fits in the system