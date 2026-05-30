# spector-gpu 🖥️

> **GPU acceleration for Spector using JNI-free Panama FFM interop with CUDA.**

`spector-gpu` accelerates batch vector similarity calculations by offloading distance calculations to NVIDIA GPUs. Using Project Panama's Foreign Function & Memory (FFM) API, it loads CUDA dynamic libraries (`nvcuda.dll` or `libcuda.so`) and binds memory buffers directly to GPU contexts without writing any JNI C++ code.

---

## 🏗️ Core Architecture & Roles

1. **CUDA Kernel Loader (`CudaKernelLoader`):** Loads compiled CUDA PTX/SASS kernels and runs JNI-free host/device FFM commands.
2. **GPU Vector Store (`GpuVectorStore`):** Allocates page-locked host memory (pinned RAM) and copies vector blocks directly to device memory (VRAM).
3. **Batch Similarity (`GpuSimilarityKernel`):** Executes parallel matrix-multiplication kernels on GPU cores, achieving up to 4× speedups over AVX-512 for batch queries of size $N \geq 100{,}000$.

---

## 🚀 Key APIs

### GPU Initialization & Context Allocation
```java
if (GpuContext.isAvailable()) {
    System.out.println("CUDA Toolkit detected! Allocating GPU resources...");
    
    try (GpuContext ctx = GpuContext.create()) {
        // Allocate pinned host/device buffers
        GpuVectorStore store = new GpuVectorStore(ctx, dimensions, capacity);
        store.put(0, vector);
    }
}
```
