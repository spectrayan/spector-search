# 🗺️ Roadmap

Spector Search is under active development. This page details planned improvements, their projected impact, and implementation status.

---

## Compression & Quantization

### ✅ VASQ-4 — Half-Precision VASQ (INT4 Codes) {#vasq-4}

!!! success "Completed"
    Implemented and merged. Available via `SpectorEngine.builder().vasq4()` or `QuantizedHnswIndex.vasq4(...)`.

Replace INT8 `[-127, 127]` codes with INT4 `[-7, 7]` codes in the VASQ pipeline. The FWHT rotation still equalizes variance, so INT4 quantization error remains uniformly distributed — just at a coarser granularity (15 levels vs 255).

**Memory layout:**
```
[float32 normSq (4 bytes)] [INT4 × paddedDim nibble-packed (paddedDim/2 bytes)]
```

| Dims | Current VASQ-8 | VASQ-4 | Compression vs float32 |
|------|---------------|--------|----------------------|
| 384 → 512 | 516 B | 260 B | **5.9×** |
| 768 → 1024 | 1028 B | 516 B | **6.0×** |
| 4096 | 4100 B | 2052 B | **8.0×** |

**Recall:**

- Without rescore: ~95–97% recall@10
- With 3× oversampling rescore: **~97–99% recall@10**

**Key design decisions:**

- Separate `Vasq4Encoder` / `Vasq4SimdKernel` classes (not parameterizing VASQ-8) to avoid impacting existing code
- Offset encoding `[0, 14]` keeps byte values non-negative for correct `castShape` sign extension
- Deinterleaved hi/lo query arrays match nibble layout for natural SIMD ILP
- Tighter clipping (2.5σ vs 3.0σ) optimizes for 15 quantization levels

---

### 🔜 Padding-Aware Storage — Skip Zero Dimensions {#padding-aware}

!!! info "Status: Planned (next)"
    Low effort, zero recall loss for L2 distance. Highest ROI pending improvement.

VASQ pads vectors to the next power-of-two dimensionality (e.g., 768 → 1024), adding wasted bytes. The padded dimensions are zero-filled before FWHT, so their rotated codes are predictable. We can **store only the first `originalDim` codes** and reconstruct padded codes at query time.

| Dims | paddedDim | Current VASQ-8 | Padding-Aware | Savings |
|------|-----------|---------------|---------------|---------|
| 384 | 512 | 516 B | 388 B | **25%** |
| 768 | 1024 | 1028 B | 772 B | **25%** |
| 1536 | 2048 | 2052 B | 1540 B | **25%** |
| 4096 | 4096 | 4100 B | 4100 B | 0% (already pow2) |

**Recall impact:** **None** for L2 distance — padded dimensions contribute a constant offset that doesn't affect ranking.

!!! warning "SIMD Tail Loop"
    The current SIMD kernel exploits `paddedDim % VL == 0` to avoid tail loops. Storing only `originalDim` codes breaks this, requiring either a scalar tail loop or alignment padding to the next SIMD boundary (e.g., round up to multiple of 16 bytes).

**Changes required:**

- `VasqEncoder` / `Vasq4Encoder`: Store only `originalDim` codes, update `bytesPerVector()`
- `VasqSimdKernel` / `Vasq4SimdKernel`: Handle non-power-of-2 loop bound (SIMD-aligned padding recommended)

---

### 🔜 Norm Header Compression — float32 → float16 {#norm-f16}

!!! info "Status: Planned (next)"
    Very low effort. Negligible recall impact.

The 4-byte `float32 exactNormSq` header can be compressed to 2 bytes using `float16` (half-precision). Java 21+ provides `Float.floatToFloat16()` and `Float.float16ToFloat()` for lossless conversion.

**Savings:** 2 bytes per vector. Small absolute savings but trivial to implement.

| Combined with | Before | After | Savings |
|---------------|--------|-------|---------|
| VASQ-8 (768-dim) | 1028 B | 1026 B | 0.2% |
| VASQ-4 (768-dim) | 516 B | 514 B | 0.4% |
| Padding-aware VASQ-8 (768-dim) | 772 B | 770 B | 0.3% |

**Recall impact:** < 0.01% — `float16` has ~3 decimal digits of precision. For L2 ranking, the norm header is a per-vector constant that shifts all distances equally.

**Changes required:**

- `VasqEncoder` / `Vasq4Encoder`: Use `Float.floatToFloat16()` for 2-byte header write
- `VasqSimdKernel` / `Vasq4SimdKernel`: Read with `Float.float16ToFloat(segment.get(JAVA_SHORT, offset))`

---

### 🔬 VASQ-PQ Hybrid — Product Quantization of VASQ Residuals {#vasq-pq}

!!! note "Status: Future Research"
    Very high implementation effort. Most aggressive compression option.

After FWHT rotation, instead of scalar INT8/INT4 quantization, apply **Product Quantization** to the rotated coordinates. The FWHT rotation makes coordinates near-independent (isotropized), which is the ideal input distribution for PQ — similar to how FAISS's OPQ (Optimized PQ) works, but using FWHT instead of a learned rotation.

**Memory layout:**
```
[float32 normSq (4 bytes)] [PQ codes: M bytes (one centroid ID per subspace)]
```

With M=16 subspaces, K=256 centroids:

| Dims | Float32 | VASQ-8 | VASQ-PQ (M=16) | Compression vs float32 |
|------|---------|--------|----------------|----------------------|
| 768 | 3,072 B | 1,028 B | 20 B | **154×** |
| 4096 | 16,384 B | 4,100 B | 68 B | **241×** |

**Recall impact:**

- PQ on FWHT-rotated residuals: ~85–93% recall@10
- FWHT rotation gives ~3–5% recall advantage over naive PQ (pre-decorrelates dimensions)
- Rescore with exact float32 residuals pushes recall to 95%+

**Why it works:** The FWHT rotation is essentially a free, lossless "Optimized PQ" rotation — it decorrelates dimensions without requiring an expensive SVD or learned rotation matrix. This means PQ subspaces can be independent slices of the rotated vector, which is information-theoretically optimal.

**Implementation scope:**

- Train PQ codebooks per shard (or globally after FWHT rotation)
- Asymmetric Distance Computation (ADC) lookup tables during search
- New SIMD kernel for PQ distance computation
- Integration with existing `ProductQuantizer` in `spector-index`

!!! danger "Complexity Warning"
    This is essentially building a new quantization mode. The existing `ProductQuantizer` could be adapted, but integrating it with the FWHT rotation pipeline is non-trivial. Estimated effort: 2–4 weeks.

---

### 🔬 Flat-Mode VASQ — Compress Flat-Shard Storage {#flat-vasq}

!!! note "Status: Future Research"
    Medium effort, good payoff for large flat shards.

In `SpectorShard`'s flat mode, residuals are stored as raw `float32[]`. Since all residuals in a shard share the same centroid, they have similar statistical distributions. **VASQ quantization of flat residuals** could compress flat-mode storage by ~3× without changing the shard architecture.

**Savings:**

| Scenario | Current (float32) | With VASQ | Savings |
|----------|-------------------|-----------|---------|
| 10K vectors × 768 dims | 30 MB/shard | 10 MB/shard | **3×** |
| 50K vectors × 4096 dims | 781 MB/shard | 195 MB/shard | **4×** |

**Recall impact:**

- If applied only to storage (decode for search): **None** — search uses decoded float32
- If applied to search (scan quantized codes directly): Same as VASQ-8 (~99.5%)

**Implementation scope:**

- Integrate VASQ encoding into the flat-mode ingestion path
- Modify `SpectorShard.flatScan()` to use the VASQ SIMD kernel directly
- Per-shard calibration using the shard's centroid residuals

---

### 🔴 Adaptive Bit-Width VASQ {#adaptive-bw}

!!! warning "Status: Not Recommended"
    Very high effort, marginal benefit due to FWHT already equalizing variance.

Instead of uniform INT8 across all dimensions, assign more bits to high-variance dimensions and fewer to low-variance ones (after FWHT rotation):

- Dimensions with σ > 2× median: 8 bits
- Dimensions with σ < 0.5× median: 4 bits
- Others: 6 bits

**Projected savings:** ~10–15% additional compression.

**Recall impact:** Minimal (< 0.5%) — allocating bits proportionally to variance is information-theoretically optimal.

**Why it's not recommended:** FWHT already equalizes variance by design, so the marginal gain from adaptive bit-widths is small. The implementation requires variable-length encoding, non-aligned SIMD reads, and per-dimension bit-width bookkeeping — the worst effort-to-benefit ratio of all proposed improvements.

---

## Compute & Hardware

### 🔜 GPU Kernel Dispatch {#gpu-dispatch}

!!! info "Status: Infrastructure Ready"
    CUDA context management and Panama FFM bridge are implemented. The compute kernel dispatch is pending.

Ship actual CUDA compute kernels for batch cosine similarity and HNSW neighbor selection. The existing `spector-gpu` module provides context management, memory allocation, and kernel loading via Panama FFM — the remaining work is the CUDA kernel code itself.

**Prerequisites:** CUDA Toolkit 12+ on the host machine.

**Expected impact:** 10–100× throughput improvement for batch similarity computation on large datasets (> 100K vectors).

---

### 🔬 NPU Acceleration {#npu}

!!! note "Status: Exploratory"
    Depends on Intel/AMD NPU SDK maturity.

Leverage Intel NPU (via OpenVINO) or AMD XDNA (via DirectML) for INT8 batch operations. NPUs are optimized for low-precision matrix operations, making them ideal for quantized VASQ distance computation.

**Target workloads:** INT8/INT4 batch similarity, VASQ kernel offload.

---

## Runtime & Deployment

### 🔬 WASM Runtime for Edge Deployment {#wasm}

!!! note "Status: Exploratory"
    Depends on GraalWasm or Chicory maturity for JVM → WASM compilation.

Compile the core SIMD kernels and HNSW index to WebAssembly for browser-based or edge deployment. This would enable client-side semantic search without a server round-trip.

---

### 🔬 Structured Concurrency (JEP 462) {#structured-concurrency}

!!! note "Status: Waiting for JEP Finalization"
    JEP 462 (Structured Concurrency) is still in preview as of Java 25.

Replace manual virtual thread management in `HybridSearchOrchestrator` and `SpectorCoordinator` with structured concurrency scopes. Benefits:

- Automatic cancellation propagation (if keyword search completes first, cancel vector search early)
- Cleaner error handling for fan-out/merge distributed queries
- Observable task trees for debugging

---

## Summary Table

| # | Improvement | Compression | Recall Impact | Effort | Status |
|---|------------|-------------|---------------|--------|--------|
| 1 | **VASQ-4** | 6–8× vs float32 | -2 to -4% (mitigated w/ rescore) | Medium | ✅ Done |
| 2 | **Padding-aware storage** | +25% (non-pow2 dims) | None (L2) | Low | 🔜 Next |
| 3 | **Norm header f16** | +2 bytes/vec | Negligible | Very Low | 🔜 Next |
| 4 | **VASQ-PQ hybrid** | 16–32× vs float32 | -7 to -15% | Very High | 🔬 Research |
| 5 | **Flat-mode VASQ** | 3× on flat shards | None or -0.5% | Medium | 🔬 Research |
| 6 | **Adaptive bit-width** | ~10–15% | Negligible | Very High | 🔴 Not planned |
| 7 | **GPU kernel dispatch** | N/A (compute) | N/A | Medium | 🔜 Infra ready |
| 8 | **NPU acceleration** | N/A (compute) | N/A | High | 🔬 Exploratory |
| 9 | **WASM edge runtime** | N/A (deployment) | N/A | High | 🔬 Exploratory |
| 10 | **Structured concurrency** | N/A (runtime) | N/A | Low | ⏳ Waiting JEP |
