// Spector Search — SVASQ Quantized Distance CUDA Kernels
//
// Asymmetric distance computation: query in float32, database in INT8 quantized.
// Matches the formula from SvasqSimdKernel exactly.
//
// Compile: nvcc -ptx -arch=sm_75 svasq_distance.cu -o svasq_distance.ptx
//
// Grid: N blocks (one per database vector)
// Block: min(D, 256) threads

#include <cuda_fp16.h>

extern "C" {

/**
 * SVASQ-8 asymmetric L2 distance.
 * L2 ≈ exactNormSq + constL2Q - 2 × dot(qTilde, z_int8)
 *
 * @param qTilde    pre-scaled query vector (D floats, = q_rot × scale)
 * @param codes     INT8 quantized codes (N × D bytes, signed)
 * @param norms     float16 norm headers (N × 2 bytes)
 * @param results   output distances (N floats)
 * @param constL2Q  query-side L2 constant (‖q‖² - 2·C(q))
 * @param N         number of database vectors
 * @param D         vector dimensionality (padded)
 */
__global__ void svasq8_l2_distance(const float* __restrict__ qTilde,
                                    const signed char* __restrict__ codes,
                                    const __half* __restrict__ norms,
                                    float* __restrict__ results,
                                    float constL2Q, int N, int D) {
    int idx = blockIdx.x;
    if (idx >= N) return;

    extern __shared__ float shared[];
    int tid = threadIdx.x;
    float acc = 0.0f;

    const signed char* z = codes + (long long)idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        float qt = qTilde[d];
        float zf = (float)z[d];       // signed INT8 → float32
        acc += qt * zf;
    }

    shared[tid] = acc;
    __syncthreads();

    // Block-level reduction
    for (int s = blockDim.x / 2; s > 0; s >>= 1) {
        if (tid < s) shared[tid] += shared[tid + s];
        __syncthreads();
    }

    if (tid == 0) {
        float dot = shared[0];
        float exactNormSq = __half2float(norms[idx]);
        float l2 = exactNormSq + constL2Q - 2.0f * dot;
        results[idx] = fmaxf(l2, 0.0f);  // clamp to non-negative
    }
}

/**
 * SVASQ-8 asymmetric dot product (inner product).
 * IP ≈ dot(qTilde, z_int8) + dotOffset
 *
 * @param qTilde    pre-scaled query vector (D floats)
 * @param codes     INT8 quantized codes (N × D bytes, signed)
 * @param results   output scores (N floats)
 * @param dotOffset query-side mean correction constant
 * @param N         number of database vectors
 * @param D         vector dimensionality (padded)
 */
__global__ void svasq8_dot_product(const float* __restrict__ qTilde,
                                    const signed char* __restrict__ codes,
                                    float* __restrict__ results,
                                    float dotOffset, int N, int D) {
    int idx = blockIdx.x;
    if (idx >= N) return;

    extern __shared__ float shared[];
    int tid = threadIdx.x;
    float acc = 0.0f;

    const signed char* z = codes + (long long)idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        acc += qTilde[d] * (float)z[d];
    }

    shared[tid] = acc;
    __syncthreads();

    for (int s = blockDim.x / 2; s > 0; s >>= 1) {
        if (tid < s) shared[tid] += shared[tid + s];
        __syncthreads();
    }

    if (tid == 0) {
        results[idx] = shared[0] + dotOffset;
    }
}

} // extern "C"
