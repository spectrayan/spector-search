// Spector Search — CUDA Batch Similarity Kernels
//
// These kernels compute similarity metrics between a query vector 
// and N database vectors in parallel.
//
// To compile: nvcc -ptx -o batch_similarity.ptx batch_similarity.cu
//
// Grid layout: N blocks (one per database vector)
// Block layout: min(dims, 256) threads (cooperative reduction)

extern "C" {

/**
 * Batch cosine similarity: computes cosine(query, database[i]) for all i in [0, N).
 *
 * @param query    query vector (D floats)
 * @param database database vectors (N*D floats, row-major)
 * @param results  output array (N floats)
 * @param N        number of database vectors
 * @param D        vector dimensionality
 */
__global__ void batch_cosine(const float* query, const float* database,
                              float* results, int N, int D) {
    int idx = blockIdx.x;  // which database vector
    if (idx >= N) return;
    
    extern __shared__ float shared[];
    float* s_dot  = shared;
    float* s_qn   = shared + blockDim.x;
    float* s_dn   = shared + 2 * blockDim.x;

    int tid = threadIdx.x;
    float dot_acc = 0.0f, qn_acc = 0.0f, dn_acc = 0.0f;

    // Each thread processes multiple dimensions in stride
    const float* db = database + idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        float q = query[d];
        float v = db[d];
        dot_acc += q * v;
        qn_acc  += q * q;
        dn_acc  += v * v;
    }

    s_dot[tid] = dot_acc;
    s_qn[tid]  = qn_acc;
    s_dn[tid]  = dn_acc;
    __syncthreads();

    // Block-level reduction (power-of-2 stride)
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
        results[idx] = (denom > 0.0f) ? s_dot[0] / denom : 0.0f;
    }
}

/**
 * Batch dot product: computes dot(query, database[i]) for all i in [0, N).
 */
__global__ void batch_dot(const float* query, const float* database,
                           float* results, int N, int D) {
    int idx = blockIdx.x;
    if (idx >= N) return;

    extern __shared__ float shared[];
    int tid = threadIdx.x;
    float acc = 0.0f;

    const float* db = database + idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        acc += query[d] * db[d];
    }

    shared[tid] = acc;
    __syncthreads();

    for (int s = blockDim.x / 2; s > 0; s >>= 1) {
        if (tid < s) shared[tid] += shared[tid + s];
        __syncthreads();
    }

    if (tid == 0) results[idx] = shared[0];
}

/**
 * Batch L2 distance: computes ||query - database[i]||² for all i in [0, N).
 */
__global__ void batch_l2(const float* query, const float* database,
                          float* results, int N, int D) {
    int idx = blockIdx.x;
    if (idx >= N) return;

    extern __shared__ float shared[];
    int tid = threadIdx.x;
    float acc = 0.0f;

    const float* db = database + idx * D;
    for (int d = tid; d < D; d += blockDim.x) {
        float diff = query[d] - db[d];
        acc += diff * diff;
    }

    shared[tid] = acc;
    __syncthreads();

    for (int s = blockDim.x / 2; s > 0; s >>= 1) {
        if (tid < s) shared[tid] += shared[tid + s];
        __syncthreads();
    }

    if (tid == 0) results[idx] = shared[0];
}

} // extern "C"
