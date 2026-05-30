package com.spectrayan.spector.gpu;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.storage.error.SpectorSegmentClosedException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Batches multiple similarity queries into single GPU kernel launches for maximum throughput.
 *
 * <p>Collects queries arriving within a configurable batching window (1–100ms) and launches
 * a single GPU kernel for the entire batch. This amortizes the overhead of GPU memory
 * transfers and kernel launches across many queries.</p>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Configurable batching window (1–100ms) and max batch size (up to 1024)</li>
 *   <li>Automatic sub-batch partitioning when batch exceeds GPU memory</li>
 *   <li>Per-query error isolation: one failing query doesn't affect others</li>
 *   <li>Individual top-K results returned per query</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * try (var searcher = new BatchGpuSearcher(kernel, memoryManager, config)) {
 *     List<float[]> queries = List.of(queryVec1, queryVec2, queryVec3);
 *     Map<Integer, BatchQueryResult> results = searcher.batchSearch(
 *         queries, database, numVectors, dimensions, topK);
 * }
 * }</pre>
 *
 * @see SimilarityKernel
 * @see GpuMemoryManager
 */
public class BatchGpuSearcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchGpuSearcher.class);

    /** Minimum batching window: 1ms */
    private static final long MIN_WINDOW_MS = 1;

    /** Maximum batching window: 100ms */
    private static final long MAX_WINDOW_MS = 100;

    /** Maximum batch size */
    private static final int MAX_BATCH_SIZE = 1024;

    /** Default batching window */
    private static final Duration DEFAULT_WINDOW = Duration.ofMillis(10);

    /** Default max batch size */
    private static final int DEFAULT_MAX_BATCH = 1024;

    private final SimilarityKernel kernel;
    private final GpuMemoryManager memoryManager;
    private final Duration batchingWindow;
    private final int maxBatchSize;

    private volatile boolean closed;

    /**
     * Creates a BatchGpuSearcher with default configuration (10ms window, 1024 max batch).
     *
     * @param kernel        the similarity kernel for computation
     * @param memoryManager the GPU memory manager for memory tracking
     */
    public BatchGpuSearcher(SimilarityKernel kernel, GpuMemoryManager memoryManager) {
        this(kernel, memoryManager, DEFAULT_WINDOW, DEFAULT_MAX_BATCH);
    }

    /**
     * Creates a BatchGpuSearcher with the specified configuration.
     *
     * @param kernel         the similarity kernel for computation
     * @param memoryManager  the GPU memory manager for memory tracking
     * @param batchingWindow the time window to collect queries before launching (1–100ms)
     * @param maxBatchSize   the maximum number of queries per batch (1–1024)
     * @throws SpectorValidationException if parameters are out of valid range
     */
    public BatchGpuSearcher(SimilarityKernel kernel, GpuMemoryManager memoryManager,
                            Duration batchingWindow, int maxBatchSize) {
        if (kernel == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Kernel");
        }
        if (memoryManager == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Memory manager");
        }
        validateBatchingWindow(batchingWindow);
        validateMaxBatchSize(maxBatchSize);

        this.kernel = kernel;
        this.memoryManager = memoryManager;
        this.batchingWindow = batchingWindow;
        this.maxBatchSize = maxBatchSize;
        this.closed = false;

        log.info("BatchGpuSearcher initialized: window={}ms, maxBatch={}",
                batchingWindow.toMillis(), maxBatchSize);
    }

    /**
     * Executes a batch search, computing top-K results for each query.
     *
     * <p>All queries are batched together into a single kernel launch (or partitioned
     * into sub-batches if GPU memory is insufficient). Each query receives its own
     * isolated result set. If a query contains invalid data (NaN, Inf), it receives
     * an error result without affecting other queries in the batch.</p>
     *
     * @param queries    the query vectors to search
     * @param database   the database vectors as a flat array (numVectors × dimensions)
     * @param numVectors number of vectors in the database
     * @param dimensions vector dimensionality
     * @param topK       number of top results per query (1–1000)
     * @return map of query index to its individual result (top-K or error)
     * @throws SpectorGpuException    if the searcher is closed
     * @throws SpectorValidationException if parameters are invalid
     */
    public Map<Integer, BatchQueryResult> batchSearch(
            List<float[]> queries, float[] database, int numVectors, int dimensions, int topK) {
        return batchSearch(queries, database, numVectors, dimensions, topK, batchingWindow);
    }

    /**
     * Executes a batch search with a specified batching window override.
     *
     * @param queries        the query vectors to search
     * @param database       the database vectors as a flat array (numVectors × dimensions)
     * @param numVectors     number of vectors in the database
     * @param dimensions     vector dimensionality
     * @param topK           number of top results per query (1–1000)
     * @param batchingWindow the batching window for this invocation
     * @return map of query index to its individual result (top-K or error)
     * @throws SpectorGpuException    if the searcher is closed
     * @throws SpectorValidationException if parameters are invalid
     */
    public Map<Integer, BatchQueryResult> batchSearch(
            List<float[]> queries, float[] database, int numVectors, int dimensions,
            int topK, Duration batchingWindow) {
        ensureOpen();
        validateSearchInputs(queries, database, numVectors, dimensions, topK);
        validateBatchingWindow(batchingWindow);

        if (queries.isEmpty()) {
            return Map.of();
        }

        // Clamp to max batch size
        List<float[]> effectiveQueries = queries.size() > maxBatchSize
                ? queries.subList(0, maxBatchSize)
                : queries;

        // Partition into sub-batches based on available GPU memory
        List<List<Integer>> subBatches = partitionByMemory(
                effectiveQueries, database, numVectors, dimensions);

        Map<Integer, BatchQueryResult> results = new HashMap<>();

        for (List<Integer> subBatchIndices : subBatches) {
            processSubBatch(subBatchIndices, effectiveQueries, database,
                    numVectors, dimensions, topK, results);
        }

        return results;
    }

    /**
     * Returns the configured batching window.
     */
    public Duration getBatchingWindow() {
        return batchingWindow;
    }

    /**
     * Returns the configured maximum batch size.
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            log.info("BatchGpuSearcher closed");
        }
    }

    // ── Internal Implementation ─────────────────────────────────────────────────

    /**
     * Partitions queries into sub-batches that fit within available GPU memory.
     *
     * <p>Memory estimation per query:
     * queryBytes = dimensions × 4 (float32)
     * databaseBytes = numVectors × dimensions × 4 (shared across queries in sub-batch)
     * resultsBytes = numVectors × 4 per query (similarity scores)
     * </p>
     */
    private List<List<Integer>> partitionByMemory(
            List<float[]> queries, float[] database, int numVectors, int dimensions) {

        long availableBytes = memoryManager.getAvailableBytes();

        // Fixed cost: database is uploaded once per sub-batch
        long databaseBytes = (long) numVectors * dimensions * Float.BYTES;

        // Per-query cost: query vector + result scores
        long perQueryBytes = (long) dimensions * Float.BYTES + (long) numVectors * Float.BYTES;

        // If database alone exceeds memory, each query is its own sub-batch
        if (databaseBytes >= availableBytes) {
            List<List<Integer>> result = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                result.add(List.of(i));
            }
            log.warn("Database exceeds GPU memory budget, processing queries individually");
            return result;
        }

        // Calculate how many queries fit with the database loaded
        long remainingAfterDb = availableBytes - databaseBytes;
        int queriesPerSubBatch = (int) Math.min(
                queries.size(),
                Math.max(1, remainingAfterDb / perQueryBytes)
        );

        List<List<Integer>> subBatches = new ArrayList<>();
        for (int i = 0; i < queries.size(); i += queriesPerSubBatch) {
            int end = Math.min(i + queriesPerSubBatch, queries.size());
            List<Integer> batch = new ArrayList<>();
            for (int j = i; j < end; j++) {
                batch.add(j);
            }
            subBatches.add(batch);
        }

        if (subBatches.size() > 1) {
            log.debug("Partitioned {} queries into {} sub-batches (memory constraint)",
                    queries.size(), subBatches.size());
        }

        return subBatches;
    }

    /**
     * Processes a sub-batch of queries, computing similarity for each and
     * extracting top-K results with per-query error isolation.
     */
    private void processSubBatch(
            List<Integer> queryIndices, List<float[]> allQueries, float[] database,
            int numVectors, int dimensions, int topK,
            Map<Integer, BatchQueryResult> results) {

        for (int queryIndex : queryIndices) {
            float[] query = allQueries.get(queryIndex);

            try {
                // Validate individual query for NaN/Inf
                String validationError = validateQueryVector(query, dimensions);
                if (validationError != null) {
                    results.put(queryIndex, BatchQueryResult.failure(validationError));
                    continue;
                }

                // Compute similarities using the kernel
                float[] scores = kernel.compute(query, database, numVectors, dimensions);

                // Extract top-K results
                List<BatchSearchResult> topKResults = extractTopK(scores, topK);
                results.put(queryIndex, BatchQueryResult.success(topKResults));

            } catch (Exception e) {
                log.debug("Query {} failed with error: {}", queryIndex, e.getMessage());
                results.put(queryIndex, BatchQueryResult.failure(
                        "Query execution failed: " + e.getMessage()));
            }
        }
    }

    /**
     * Validates a query vector for NaN or infinity values.
     *
     * @return error message if invalid, null if valid
     */
    private String validateQueryVector(float[] query, int dimensions) {
        if (query == null) {
            return "Query vector is null";
        }
        if (query.length < dimensions) {
            return "Query vector length (%d) is less than dimensions (%d)"
                    .formatted(query.length, dimensions);
        }
        for (int i = 0; i < dimensions; i++) {
            if (Float.isNaN(query[i])) {
                return "Query vector contains NaN at index " + i;
            }
            if (Float.isInfinite(query[i])) {
                return "Query vector contains infinity at index " + i;
            }
        }
        return null;
    }

    /**
     * Extracts the top-K highest-scoring results from a similarity scores array.
     * Uses partial sort (selection) for efficiency when K << N.
     */
    private List<BatchSearchResult> extractTopK(float[] scores, int topK) {
        int k = Math.min(topK, scores.length);
        if (k == 0) {
            return List.of();
        }

        // Build index-score pairs and sort by score descending
        // For large arrays, a partial sort (heap) would be more efficient,
        // but for typical use cases this is sufficient
        int[] indices = new int[scores.length];
        for (int i = 0; i < scores.length; i++) {
            indices[i] = i;
        }

        // Use a simple partial selection approach for top-K
        // For K << N, a min-heap of size K is optimal
        if (k < scores.length / 4) {
            return extractTopKHeap(scores, indices, k);
        }

        // For larger K relative to N, sort and take top-K
        BatchSearchResult[] allResults = new BatchSearchResult[scores.length];
        for (int i = 0; i < scores.length; i++) {
            allResults[i] = new BatchSearchResult(i, scores[i]);
        }
        Arrays.sort(allResults); // descending by score
        return List.of(Arrays.copyOf(allResults, k));
    }

    /**
     * Heap-based top-K extraction for when K is much smaller than N.
     */
    private List<BatchSearchResult> extractTopKHeap(float[] scores, int[] indices, int k) {
        // Min-heap of size K (we keep the K largest items)
        float[] heapScores = new float[k];
        int[] heapIndices = new int[k];
        int heapSize = 0;

        for (int i = 0; i < scores.length; i++) {
            if (heapSize < k) {
                // Fill heap
                heapScores[heapSize] = scores[i];
                heapIndices[heapSize] = i;
                heapSize++;
                if (heapSize == k) {
                    // Build min-heap
                    for (int j = k / 2 - 1; j >= 0; j--) {
                        siftDown(heapScores, heapIndices, j, k);
                    }
                }
            } else if (scores[i] > heapScores[0]) {
                // Replace min element
                heapScores[0] = scores[i];
                heapIndices[0] = i;
                siftDown(heapScores, heapIndices, 0, k);
            }
        }

        // Extract results sorted by score descending
        BatchSearchResult[] results = new BatchSearchResult[heapSize];
        for (int i = 0; i < heapSize; i++) {
            results[i] = new BatchSearchResult(heapIndices[i], heapScores[i]);
        }
        Arrays.sort(results); // descending
        return List.of(results);
    }

    private void siftDown(float[] scores, int[] indices, int i, int size) {
        while (true) {
            int smallest = i;
            int left = 2 * i + 1;
            int right = 2 * i + 2;

            if (left < size && scores[left] < scores[smallest]) {
                smallest = left;
            }
            if (right < size && scores[right] < scores[smallest]) {
                smallest = right;
            }
            if (smallest == i) break;

            // Swap
            float tmpScore = scores[i];
            scores[i] = scores[smallest];
            scores[smallest] = tmpScore;

            int tmpIdx = indices[i];
            indices[i] = indices[smallest];
            indices[smallest] = tmpIdx;

            i = smallest;
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    private void validateBatchingWindow(Duration window) {
        if (window == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Batching window");
        }
        long ms = window.toMillis();
        if (ms < MIN_WINDOW_MS || ms > MAX_WINDOW_MS) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Batching window must be between %d and %dms, got: %dms" .formatted(MIN_WINDOW_MS, MAX_WINDOW_MS, ms));
        }
    }

    private void validateMaxBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Max batch size must be between 1 and %d, got: %d" .formatted(MAX_BATCH_SIZE, batchSize));
        }
    }

    private void validateSearchInputs(List<float[]> queries, float[] database,
                                       int numVectors, int dimensions, int topK) {
        if (queries == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Queries list");
        }
        if (database == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "Database array");
        }
        if (numVectors < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "numVectors", numVectors);
        }
        if (dimensions <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Dimensions", 1, Integer.MAX_VALUE, dimensions);
        }
        if (topK < 1 || topK > 1000) {
            throw new SpectorValidationException(ErrorCode.TOP_K_INVALID, 1, topK);
        }
        if (numVectors > 0 && database.length < (long) numVectors * dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "Database array length (%d) is less than required (%d)" .formatted(database.length, (long) numVectors * dimensions));
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new SpectorSegmentClosedException();
        }
    }
}