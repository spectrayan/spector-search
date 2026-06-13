// Spector Search — HNSW Candidate Distance CUDA Kernels
//
// Optimized for the HNSW access pattern: small batches (10-200 candidates per hop),
// repeated many times per search. Uses cooperative shared-memory reduction.
//
// Compile: nvcc -ptx -arch=sm_75 hnsw_candidates.cu -o hnsw_candidates.ptx
//
// Grid: K blocks (one per candidate vector)
// Block: min(D, 256) threads

extern "C" {

/**
 * Cosine similarity between query and each HNSW candidate.
 *
 * @param query      query vector (D floats)
 * @param candidates candidate vectors (K × D floats, row-major)
 * @param distances  output similarities (K floats)
 * @param K          number of candidates (typically 10-200)
 * @param D          vector dimensionality
 */
__global__ void hnsw_cosine_candidates(const float* query, const float* candidates,
                                        float* distances, int K, int D) {
    int idx = blockIdx.x;
    if (idx >= K) return;

    extern __shared__ float shared[];
    float* s_dot = shared;
    float* s_qn  = shared + blockDim.x;
    float* s_dn  = shared + 2 * blockDim.x;

    int tid = threadIdx.x;
    float dot_acc = 0.0f, qn_acc = 0.0f, dn_acc = 0.0f;

    const float* cand = candidates + (long long)idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        float q = query[d];
        float v = cand[d];
        dot_acc += q * v;
        qn_acc  += q * q;
        dn_acc  += v * v;
    }

    s_dot[tid] = dot_acc;
    s_qn[tid]  = qn_acc;
    s_dn[tid]  = dn_acc;
    __syncthreads();

    for (int s = blockDim.x / 2; s > 0; s >>= 1) {
        if (tid < s) {
            s_dot[tid] += s_dot[tid + s];
            s_qn[tid]  += s_qn[tid + s];
            s_dn[tid]  += s_dn[tid + s];
        }
        __syncthreads();
    }

    if (tid == 0) {
        float denom = sqrtf(s_qn[0]) * sqrtf(s_dn[0]);
        distances[idx] = (denom > 0.0f) ? s_dot[0] / denom : 0.0f;
    }
}

/**
 * L2 squared distance between query and each HNSW candidate.
 *
 * @param query      query vector (D floats)
 * @param candidates candidate vectors (K × D floats, row-major)
 * @param distances  output distances (K floats)
 * @param K          number of candidates
 * @param D          vector dimensionality
 */
__global__ void hnsw_l2_candidates(const float* query, const float* candidates,
                                    float* distances, int K, int D) {
    int idx = blockIdx.x;
    if (idx >= K) return;

    extern __shared__ float shared[];
    int tid = threadIdx.x;
    float acc = 0.0f;

    const float* cand = candidates + (long long)idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        float diff = query[d] - cand[d];
        acc += diff * diff;
    }

    shared[tid] = acc;
    __syncthreads();

    for (int s = blockDim.x / 2; s > 0; s >>= 1) {
        if (tid < s) shared[tid] += shared[tid + s];
        __syncthreads();
    }

    if (tid == 0) distances[idx] = shared[0];
}

} // extern "C"
