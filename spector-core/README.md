# spector-core 🌀

> **The high-performance SIMD-accelerated similarity and quantization math core of Spector.**

`spector-core` houses the low-level math kernels, Walsh-Hadamard transforms, and vectorized similarity operators that form the computational engine of the search platform. Written natively for Java 25 utilizing the Panama Vector API (`jdk.incubator.vector`), it compiles hardware-specific SIMD instructions (AVX2, AVX-512, and ARM NEON) on the fly, eliminating native libraries or JNI bindings.

---

## 🏗️ Core Architecture & Roles

1. **SIMD Similarity Kernels (`SimilarityKernel`):** Vectorized mathematical calculations for Euclidean ($L2^2$), Cosine, and Dot Product similarity functions. Fully optimized for 256-bit AVX2/AVX-512 lanes.
2. **Fast Walsh-Hadamard Transform (`Fwht`):** Ultra-fast, in-place $O(D \log D)$ orthogonal rotation butterflies using only addition and subtraction instructions. This spreads dynamic range variance uniformly across all dimensions.
3. **Asymmetric SIMD Quantization (`SvasqSimdKernel`):** Panama FFM-native distance calculators that evaluate off-heap INT8 codes directly against exact float32 query states, bypassing dequantization overhead.

---

## 🚀 Key APIs

### Similarity Kernels
```java
float[] a = ...;
float[] b = ...;

// High-speed SIMD L2 squared distance
float l2Squared = SimilarityKernel.L2_SQUARED.compute(a, b);

// High-speed SIMD Cosine similarity
float cosineSim = SimilarityKernel.COSINE.compute(a, b);
```

### Fast Walsh-Hadamard Transform (FWHT)
```java
float[] data = ...; // must be padded to power of 2

// In-place Walsh-Hadamard Butterfly transform
Fwht.transformInPlace(data);
```

---

## 🛠️ Performance & SIMD Lanes

The module auto-detects hardware architectures and selects optimal vector lanes at runtime:

- **AVX-512 (512-bit):** 16 float lanes per instruction (Intel Xeon, recent AMD).
- **AVX2 (256-bit):** 8 float lanes per instruction (Most modern x86 desktops/laptops).
- **NEON (128-bit):** 4 float lanes per instruction (Apple Silicon M1/M2/M3, ARM64).
