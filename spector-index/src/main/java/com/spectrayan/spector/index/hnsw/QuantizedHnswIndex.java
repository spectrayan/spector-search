package com.spectrayan.spector.index;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.BitSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.core.quantization.strategy.DistanceContext;
import com.spectrayan.spector.core.quantization.strategy.QuantizationStrategy;
import com.spectrayan.spector.core.quantization.strategy.QuantizationStrategyFactory;
import com.spectrayan.spector.core.quantization.strategy.Vasq4Strategy;
import com.spectrayan.spector.core.quantization.strategy.VasqStrategy;
import com.spectrayan.spector.core.quantization.vasq.Vasq4Encoder;
import com.spectrayan.spector.core.quantization.vasq.VasqCalibrator;
import com.spectrayan.spector.core.quantization.vasq.VasqEncoder;
import com.spectrayan.spector.core.quantization.vasq.VasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * HNSW vector index with scalar quantization (INT8, INT4, INT2, VASQ) support.
 *
 * <p>Uses a two-phase search strategy for optimal speed/recall tradeoff:</p>
 * <ol>
 *   <li><b>Coarse search</b> — traverses the HNSW graph using quantized
 *       distances via the {@link QuantizationStrategy} SPI</li>
 *   <li><b>Re-ranking</b> — recomputes exact float32 distances for the top
 *       candidates to restore full-precision recall</li>
 * </ol>
 *
 * <h3>Design — Strategy Pattern</h3>
 * <p>All quantization-type-specific logic ({@code encode}, {@code decode}, {@code distance})
 * is delegated to a single {@link QuantizationStrategy} instance created by
 * {@link QuantizationStrategyFactory}. This eliminates the switch/if-else dispatch
 * chains that previously existed in this class. Adding a new quantization type requires
 * only a new strategy implementation — this class does not change.</p>
 *
 * <h3>Quantization Types</h3>
 * <ul>
 *   <li><b>INT8</b> — one byte per dimension, auto-calibrated after first N vectors (4× compression)</li>
 *   <li><b>INT4</b> — nibble-packed, calibrated from NonUniformQuantizer (8× compression)</li>
 *   <li><b>INT2</b> — crumb-packed, calibrated from NonUniformQuantizer (16× compression)</li>
 *   <li><b>VASQ</b> — FWHT-rotated INT8, off-heap Panama SIMD kernel, auto-calibrated</li>
 *   <li><b>VASQ-4</b> — FWHT-rotated INT4 (nibble-packed), 2× more compressed than VASQ, auto-calibrated</li>
 * </ul>
 *
 * <h3>Rescore Strategy</h3>
 * <p>When the oversampling factor is greater than 1, the index retrieves
 * {@code oversamplingFactor × k} candidates using fast quantized distance,
 * then rescores them with exact float32 distances to return the true top-K.</p>
 *
 * <h3>Calibration</h3>
 * <p>For INT8 and VASQ: calibration is deferred. Vectors inserted before calibration
 * are buffered and retroactively encoded after auto-calibration triggers at
 * {@link #CALIBRATION_SAMPLE_SIZE} vectors. For INT4/INT2: the NonUniformQuantizer
 * must be pre-calibrated and provided at construction time.</p>
 *
 * @see AbstractHnswIndex
 * @see HnswIndex
 * @see QuantizationStrategy
 */
public class QuantizedHnswIndex extends AbstractHnswIndex {

    private static final Logger log = LoggerFactory.getLogger(QuantizedHnswIndex.class);

    /** Number of vectors to buffer before auto-calibrating the quantizer. */
    private static final int CALIBRATION_SAMPLE_SIZE = 10_000;

    // ── Vector storage (float32 kept for re-ranking and HNSW graph construction) ──
    private final float[][] floatVectors;

    // ── Unified off-heap storage (all quantization types) ──
    /** Off-heap segment storing all quantized vectors. Null until the first calibration. */
    private volatile MemorySegment storageSegment;
    private Arena storageArena;

    // ── Calibration state ──
    private final QuantizationType quantizationType;
    private float[][] calibrationBuffer;
    private int calibrationCount;

    /**
     * The active quantization strategy. Null before calibration completes (for auto-calibrate types).
     * Set atomically after calibration by {@link #calibrate()} or {@link #calibrateVasq()}.
     * For pre-calibrated types (INT4/INT2), set at construction.
     */
    private volatile QuantizationStrategy strategy;

    /**
     * Per-search distance context. Created locally inside {@link #searchLayerQuantized}
     * and passed as a parameter — never stored as an instance field. This keeps
     * concurrent reads on the same index safe (each search uses its own context).
     */
    // NOTE: currentQueryContext was previously a mutable instance field, which made
    // concurrent searches on the same QuantizedHnswIndex unsafe despite AbstractHnswIndex's
    // readLock. It has been moved to a method-local variable in searchLayerQuantized().

    // ── Rescore configuration ──
    private final int oversamplingFactor;

    // ── Retained for backward-compat accessors ──
    private volatile ScalarQuantizer quantizer;
    private final NonUniformQuantizer nonUniformQuantizer;
    private volatile VasqEncoder vasqEncoder;
    private volatile Vasq4Encoder vasq4Encoder;
    private final long vasqSeed;

    // ─────────────── Constructors ───────────────

    /**
     * Creates a quantized HNSW index with a pre-calibrated INT8 quantizer.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max vectors
     * @param similarityFunction distance metric
     * @param params             HNSW parameters
     * @param quantizer          pre-calibrated INT8 quantizer (null for auto-calibrate)
     */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params,
                               ScalarQuantizer quantizer) {
        this(dimensions, capacity, similarityFunction, params, quantizer,
                QuantizationType.SCALAR_INT8, null, 1);
    }

    /** Creates with auto-calibration (INT8, no oversampling). */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params) {
        this(dimensions, capacity, similarityFunction, params, null,
                QuantizationType.SCALAR_INT8, null, 1);
    }

    /**
     * Creates a VASQ HNSW index with auto-calibration.
     *
     * <p>VASQ calibration happens automatically when the first {@link #CALIBRATION_SAMPLE_SIZE}
     * vectors are inserted. All vectors (including those inserted before calibration) are
     * retroactively encoded after calibration.</p>
     *
     * @param dimensions           vector dimensionality
     * @param capacity             max vectors
     * @param similarityFunction   distance metric
     * @param params               HNSW parameters
     * @param oversamplingFactor   rescore oversampling (1 = no rescore, 3 = recommended for VASQ)
     */
    public static QuantizedHnswIndex vasq(int dimensions, int capacity,
                                           SimilarityFunction similarityFunction,
                                           HnswParams params, int oversamplingFactor) {
        return new QuantizedHnswIndex(dimensions, capacity, similarityFunction, params,
                null, QuantizationType.VASQ, null, oversamplingFactor);
    }

    /**
     * Creates a VASQ-quantized HNSW index with a <em>pre-calibrated</em> {@link VasqStrategy}.
     *
     * <p>Unlike {@link #vasq} (which auto-calibrates on the first
     * {@link #CALIBRATION_SAMPLE_SIZE} inserted vectors), this variant accepts a
     * {@link VasqStrategy} calibrated externally — typically on the full residual buffer
     * of a {@link com.spectrayan.spector.index.spectrum.SpectorShard} at promotion time.
     * This gives tighter quantization bounds because all residuals participate in
     * calibration, not just the first 10K.</p>
     *
     * <p>The strategy is active from the very first {@link #add} call — no buffering
     * phase occurs and the off-heap segment is allocated immediately.</p>
     *
     * @param dimensions           vector dimensionality
     * @param capacity             max vectors
     * @param similarityFunction   distance metric
     * @param params               HNSW parameters
     * @param preCalibrated        a fully built {@link VasqStrategy} (non-null)
     * @param oversamplingFactor   rescore oversampling (1 = no rescore, 3 = recommended)
     * @throws NullPointerException if {@code preCalibrated} is null
     */
    public static QuantizedHnswIndex vasqPreCalibrated(int dimensions, int capacity,
                                                        SimilarityFunction similarityFunction,
                                                        HnswParams params,
                                                        VasqStrategy preCalibrated,
                                                        int oversamplingFactor) {
        if (preCalibrated == null) throw new NullPointerException("preCalibrated VasqStrategy must not be null");
        return new QuantizedHnswIndex(dimensions, capacity, similarityFunction, params,
                preCalibrated, QuantizationType.VASQ, oversamplingFactor);
    }

    /**
     * Creates a VASQ-4 HNSW index with auto-calibration.
     *
     * <p>VASQ-4 uses INT4 nibble-packed codes (2× smaller than VASQ-8).
     * Calibration happens automatically when the first {@link #CALIBRATION_SAMPLE_SIZE}
     * vectors are inserted, using 4-bit scales and tighter clipping (2.5σ).</p>
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max vectors
     * @param similarityFunction distance metric
     * @param params             HNSW parameters
     * @param oversamplingFactor rescore oversampling (3 = recommended for VASQ-4)
     */
    public static QuantizedHnswIndex vasq4(int dimensions, int capacity,
                                            SimilarityFunction similarityFunction,
                                            HnswParams params, int oversamplingFactor) {
        return new QuantizedHnswIndex(dimensions, capacity, similarityFunction, params,
                null, QuantizationType.VASQ_4, null, oversamplingFactor);
    }

    /**
     * Creates a VASQ-4 HNSW index with a pre-calibrated {@link Vasq4Strategy}.
     *
     * @param dimensions         vector dimensionality
     * @param capacity           max vectors
     * @param similarityFunction distance metric
     * @param params             HNSW parameters
     * @param preCalibrated      a fully built {@link Vasq4Strategy} (non-null)
     * @param oversamplingFactor rescore oversampling
     */
    public static QuantizedHnswIndex vasq4PreCalibrated(int dimensions, int capacity,
                                                         SimilarityFunction similarityFunction,
                                                         HnswParams params,
                                                         Vasq4Strategy preCalibrated,
                                                         int oversamplingFactor) {
        if (preCalibrated == null) throw new NullPointerException("preCalibrated Vasq4Strategy must not be null");
        return new QuantizedHnswIndex(dimensions, capacity, similarityFunction, params,
                preCalibrated, QuantizationType.VASQ_4, oversamplingFactor);
    }

    /**
     * Creates a quantized HNSW index supporting INT8, INT4, INT2, or VASQ quantization
     * with configurable rescore oversampling.
     *
     * @param dimensions           vector dimensionality
     * @param capacity             max vectors
     * @param similarityFunction   distance metric
     * @param params               HNSW parameters
     * @param quantizer            pre-calibrated INT8 quantizer (null for auto-calibrate; ignored for INT4/INT2)
     * @param quantizationType     quantization type
     * @param nonUniformQuantizer  calibrated non-uniform quantizer (required for INT4/INT2, null for INT8/VASQ)
     * @param oversamplingFactor   rescore oversampling factor (1 = no rescore, &gt;1 = oversample and rescore)
     */
    public QuantizedHnswIndex(int dimensions, int capacity,
                               SimilarityFunction similarityFunction,
                               HnswParams params,
                               ScalarQuantizer quantizer,
                               QuantizationType quantizationType,
                               NonUniformQuantizer nonUniformQuantizer,
                               int oversamplingFactor) {
        super(dimensions, capacity, similarityFunction, params);

        this.quantizationType = quantizationType != null ? quantizationType : QuantizationType.SCALAR_INT8;
        this.nonUniformQuantizer = nonUniformQuantizer;
        this.oversamplingFactor = Math.max(1, oversamplingFactor);
        this.floatVectors = new float[capacity][];
        this.vasqSeed = VasqParams.DEFAULT_SEED;

        // For INT4/INT2: strategy is ready immediately (pre-calibrated quantizer required)
        // For INT8: strategy is ready if quantizer is provided; otherwise null until auto-calibrate
        // For VASQ: strategy is null until auto-calibrate
        if (this.quantizationType == QuantizationType.SCALAR_INT4
                || this.quantizationType == QuantizationType.SCALAR_INT2) {
            if (nonUniformQuantizer == null) {
                throw new IllegalArgumentException(
                        "NonUniformQuantizer is required for " + quantizationType);
            }
            this.strategy = QuantizationStrategyFactory.create(
                    this.quantizationType, null, nonUniformQuantizer, null, null, similarityFunction);
            this.quantizer = null;
            this.vasqEncoder = null;
            this.calibrationBuffer = null;
            this.calibrationCount = 0;
            // Allocate unified storage segment for packed INT4/INT2
            allocateStorageSegment(capacity, strategy.bytesPerVector());
        } else if (this.quantizationType == QuantizationType.SCALAR_INT8 && quantizer != null) {
            // Pre-calibrated INT8 — strategy ready immediately
            this.strategy = QuantizationStrategyFactory.create(
                    this.quantizationType, quantizer, null, null, null, similarityFunction);
            this.quantizer = quantizer;
            this.vasqEncoder = null;
            this.calibrationBuffer = null;
            this.calibrationCount = 0;
            allocateStorageSegment(capacity, strategy.bytesPerVector());
        } else {
            // Auto-calibrate (INT8 or VASQ) — strategy and segment are null until calibration
            this.strategy = null;
            this.quantizer = null;
            this.vasqEncoder = null;
            this.vasq4Encoder = null;
            this.calibrationBuffer = new float[Math.min(CALIBRATION_SAMPLE_SIZE, capacity)][];
            this.calibrationCount = 0;
            this.storageSegment = null;
            this.storageArena = null;
        }

        log.info("QuantizedHnswIndex created: dims={}, capacity={}, M={}, type={}, oversampling={}, strategy={}",
                dimensions, capacity, params.m(), this.quantizationType, this.oversamplingFactor,
                this.strategy != null ? "ready" : "pending-calibration");
    }

    /**
     * Private constructor for pre-calibrated VASQ or VASQ-4.
     * The strategy is immediately active; no calibration buffer is allocated.
     */
    private QuantizedHnswIndex(int dimensions, int capacity,
                                SimilarityFunction similarityFunction,
                                HnswParams params,
                                QuantizationStrategy preCalibrated,
                                QuantizationType quantType,
                                int oversamplingFactor) {
        super(dimensions, capacity, similarityFunction, params);
        this.quantizationType = quantType;
        this.nonUniformQuantizer = null;
        this.oversamplingFactor = Math.max(1, oversamplingFactor);
        this.floatVectors = new float[capacity][];
        this.vasqSeed = VasqParams.DEFAULT_SEED;
        this.strategy = preCalibrated;

        // Set the appropriate encoder accessor based on the strategy type
        if (preCalibrated instanceof VasqStrategy vs) {
            this.vasqEncoder = vs.encoder();
            this.vasq4Encoder = null;
        } else if (preCalibrated instanceof Vasq4Strategy v4s) {
            this.vasqEncoder = null;
            this.vasq4Encoder = v4s.encoder();
        } else {
            this.vasqEncoder = null;
            this.vasq4Encoder = null;
        }
        this.quantizer = null;
        this.calibrationBuffer = null;
        this.calibrationCount = 0;
        allocateStorageSegment(capacity, preCalibrated.bytesPerVector());
        log.info("QuantizedHnswIndex created with pre-calibrated {}: dims={}, capacity={}, M={}, bpv={}, oversampling={}",
                quantType, dimensions, capacity, params.m(), preCalibrated.bytesPerVector(), this.oversamplingFactor);
    }

    // ─────────────── Template method implementations ───────────────

    @Override
    protected float computeDistance(float[] query, int nodeIdx) {
        return similarityFunction.computeForRanking(query, floatVectors[nodeIdx]);
    }

    @Override
    protected float[] getNodeVector(int nodeIdx) {
        return floatVectors[nodeIdx];
    }

    @Override
    protected void storeVector(int nodeIdx, float[] vector) {
        // Defensive copy: the caller (add()) may mutate the passed vector after this returns.
        // SpectorShard's ThreadLocal residual scratch is overwritten on the next add(), so the copy
        // is necessary for the normal hot path. addOwned() sets skipCopy to bypass this.
        floatVectors[nodeIdx] = skipCopy.get()[0] ? vector : Arrays.copyOf(vector, vector.length);

        if (strategy == null) {
            // Pre-calibration buffer phase (INT8 auto or VASQ auto)
            bufferForCalibration(vector, nodeIdx);
        } else {
            // Strategy is ready — encode directly into the off-heap segment
            long offset = (long) nodeIdx * strategy.bytesPerVector();
            strategy.encode(vector, storageSegment, offset);
        }
    }

    /**
     * ThreadLocal flag used by {@link #addOwned} to tell {@link #storeVector} to skip the
     * defensive {@code Arrays.copyOf}. A {@code boolean[1]} (not {@code Boolean}) is used so
     * it can be mutated inside the lambda without a wrapper allocation.
     */
    private final ThreadLocal<boolean[]> skipCopy = ThreadLocal.withInitial(() -> new boolean[]{false});

    /**
     * Bulk-insert variant that transfers ownership of {@code vector} to this index,
     * skipping the defensive {@link Arrays#copyOf} that {@link #add} performs.
     *
     * <p><b>Ownership contract</b>: the caller must NOT mutate or reuse {@code vector}
     * after this call returns. {@link com.spectrayan.spector.index.spectrum.SpectorShard#promote}
     * satisfies this contract — it extracts sub-arrays from its flat buffer and nulls the
     * buffer immediately after the bulk insert completes.</p>
     *
     * <p>For a shard of 20 000 vectors at D=768, this avoids ~61 MB of copy work
     * compared to the standard {@link #add} path.</p>
     *
     * @param id         external document ID
     * @param storeIndex external store index
     * @param vector     float32 vector — ownership is transferred to this index
     */
    public void addOwned(String id, int storeIndex, float[] vector) {
        boolean[] flag = skipCopy.get();
        flag[0] = true;
        try {
            add(id, storeIndex, vector);  // AbstractHnswIndex.add() — acquires writeLock, calls storeVector()
        } finally {
            flag[0] = false;
        }
    }

    private void bufferForCalibration(float[] vector, int nodeIdx) {

        if (calibrationCount < calibrationBuffer.length) {
            calibrationBuffer[calibrationCount++] = vector;
        }
        if (calibrationCount >= calibrationBuffer.length
                || calibrationCount >= CALIBRATION_SAMPLE_SIZE) {
            // Trigger calibration for INT8, VASQ, or VASQ_4
            if (quantizationType == QuantizationType.VASQ) {
                calibrateVasq();
            } else if (quantizationType == QuantizationType.VASQ_4) {
                calibrateVasq4();
            } else {
                calibrate();
            }
        }
    }

    // ─────────────── Overridden search with quantized re-ranking ───────────────

    @Override
    public ScoredResult[] search(float[] query, int k) {
        if (query.length != dimensions) {
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + query.length);
        }
        if (nodeCount == 0) {
            return new ScoredResult[0];
        }

        int ef = Math.max(k, params.efSearch());
        int currentNode = entryPoint;

        // Phase 1: Greedy descent through upper layers (uses exact float for precision)
        for (int lc = maxLevel; lc > 0; lc--) {
            currentNode = greedyClosest(query, currentNode, lc);
        }

        // Phase 2: Search at layer 0 using quantized distance (if strategy is ready)
        NeighborQueue candidates;
        if (strategy != null) {
            int effectiveEf = oversamplingFactor > 1
                    ? Math.max(ef, oversamplingFactor * k)
                    : ef;
            candidates = searchLayerQuantized(query, currentNode, effectiveEf);
        } else {
            // No strategy yet (pre-calibration phase) — use exact float distances
            candidates = searchLayer(query, currentNode, ef, 0);
            return mapToStoreIndices(candidates.toSortedResults(ids, similarityFunction.higherIsBetter()));
        }

        // Phase 3: Rescore — re-rank coarse candidates with exact float distances
        if (oversamplingFactor <= 1) {
            ScoredResult[] sorted = candidates.toSortedResults(ids, similarityFunction.higherIsBetter());
            int resultCount = Math.min(k, sorted.length);
            ScoredResult[] trimmed = resultCount == sorted.length ? sorted : Arrays.copyOf(sorted, resultCount);
            return mapToStoreIndices(trimmed);
        }

        int[] candidateIndices = candidates.indicesUnsorted();
        int reRankCount = candidateIndices.length;
        ScoredResult[] exactResults = new ScoredResult[reRankCount];
        for (int i = 0; i < reRankCount; i++) {
            int nodeIdx = candidateIndices[i];
            float exactScore = similarityFunction.computeForRanking(query, floatVectors[nodeIdx]);
            exactResults[i] = new ScoredResult(ids[nodeIdx], nodeIdx, exactScore);
        }

        if (similarityFunction.higherIsBetter()) {
            Arrays.sort(exactResults);
        } else {
            Arrays.sort(exactResults, ScoredResult::compareAscending);
        }

        int resultCount = Math.min(k, exactResults.length);
        ScoredResult[] rescored = Arrays.copyOf(exactResults, resultCount);
        return mapToStoreIndices(rescored);
    }

    private ScoredResult[] mapToStoreIndices(ScoredResult[] results) {
        if (results == null || results.length == 0) return results;
        ScoredResult[] mapped = new ScoredResult[results.length];
        for (int i = 0; i < results.length; i++) {
            ScoredResult r = results[i];
            mapped[i] = new ScoredResult(r.id(), storeIndices[r.index()], r.score());
        }
        return mapped;
    }

    // ─────────────── Quantized layer-0 search ───────────────

    /**
     * Layer-0 search using quantized distances for coarse filtering.
     *
     * <p>Creates the {@link DistanceContext} locally (not as an instance field) so that
     * concurrent searches on the same index are safe — each search thread has its own
     * context, and the FWHT rotate scratch is already per-thread via ThreadLocal.</p>
     */
    private NeighborQueue searchLayerQuantized(float[] query, int entryNode, int ef) {
        // Context is local — zero shared mutable state between concurrent searches
        DistanceContext ctx = strategy.prepareQueryContext(query);

        // Reuse per-thread BitSet from parent class — avoids per-search allocation
        BitSet visited = visitedBitSetLocal.get();
        visited.clear();
        NeighborQueue candidates = new NeighborQueue(ef + 1, ef, maxHeap());
        NeighborQueue workQueue = new NeighborQueue(ef + 1, minHeap());

        float entryDist = computeQuantizedDistance(entryNode, ctx);
        candidates.add(entryNode, entryDist);
        workQueue.add(entryNode, entryDist);
        visited.set(entryNode);

        while (!workQueue.isEmpty()) {
            float currentDist = workQueue.topScore();
            int current = workQueue.poll();

            if (candidates.size() >= ef && !isBetter(currentDist, candidates.topScore())) {
                break;
            }

            int[] nbrs = getNeighbors(current, 0);
            for (int neighbor : nbrs) {
                if (!visited.get(neighbor)) {
                    visited.set(neighbor);
                    float dist = computeQuantizedDistance(neighbor, ctx);
                    if (candidates.size() < ef || isBetter(dist, candidates.topScore())) {
                        candidates.add(neighbor, dist);
                        workQueue.add(neighbor, dist);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Computes quantized distance from a stored vector to the current search query.
     *
     * <p>Reads from the unified off-heap {@link #storageSegment} using the active
     * {@link QuantizationStrategy} and the per-search {@link DistanceContext} passed
     * directly as a parameter (not stored as an instance field, ensuring thread safety).</p>
     *
     * @param nodeIdx the index of the candidate node
     * @param ctx     the per-search distance context created by {@link #searchLayerQuantized}
     * @return approximate distance
     */
    private float computeQuantizedDistance(int nodeIdx, DistanceContext ctx) {
        long offset = (long) nodeIdx * strategy.bytesPerVector();
        return strategy.distance(storageSegment, offset, ctx);
    }

    // ─────────────── Calibration ───────────────

    /**
     * Auto-calibrates the INT8 scalar quantizer from buffered vectors, builds the
     * strategy, allocates the off-heap segment, and retroactively encodes all buffered
     * vectors.
     */
    private synchronized void calibrate() {
        if (strategy != null) return; // already calibrated (concurrent trigger)
        float[][] sample = Arrays.copyOf(calibrationBuffer, calibrationCount);
        ScalarQuantizer sq = ScalarQuantizer.calibrate(sample, dimensions);
        this.quantizer = sq;

        this.strategy = QuantizationStrategyFactory.create(
                QuantizationType.SCALAR_INT8, sq, null, null, null, similarityFunction);
        allocateStorageSegment(capacity, strategy.bytesPerVector());

        log.info("QuantizedHnswIndex INT8 auto-calibrated from {} sample vectors", calibrationCount);

        // Retroactively encode all buffered vectors
        for (int i = 0; i < nodeCount; i++) {
            if (floatVectors[i] != null) {
                long offset = (long) i * strategy.bytesPerVector();
                strategy.encode(floatVectors[i], storageSegment, offset);
            }
        }

        calibrationBuffer = null;
        calibrationCount = 0;
    }

    /**
     * Auto-calibrates the VASQ encoder from buffered vectors, builds the strategy,
     * allocates the off-heap segment, and retroactively encodes all buffered vectors.
     *
     * <p>Uses {@link VasqCalibrator#calibrate(float[][], int, int, long)} to avoid
     * the previous {@code Arrays.copyOf} + {@code Arrays.asList} wrapper.
     */
    private synchronized void calibrateVasq() {
        if (strategy != null) return; // already calibrated
        VasqParams vParams = VasqCalibrator.calibrate(
                calibrationBuffer, calibrationCount, dimensions, vasqSeed);
        VasqEncoder enc = new VasqEncoder(vParams);
        this.vasqEncoder = enc;

        this.strategy = new VasqStrategy(enc, similarityFunction);
        allocateStorageSegment(capacity, strategy.bytesPerVector());

        log.info("QuantizedHnswIndex VASQ auto-calibrated: {} sample vectors, paddedDim={}, bpv={}",
                calibrationCount, vParams.paddedDim(), strategy.bytesPerVector());

        // Retroactively encode all vectors inserted before calibration
        for (int i = 0; i < nodeCount; i++) {
            if (floatVectors[i] != null) {
                long offset = (long) i * strategy.bytesPerVector();
                strategy.encode(floatVectors[i], storageSegment, offset);
            }
        }

        calibrationBuffer = null;
        calibrationCount = 0;
    }

    /**
     * Auto-calibrates the VASQ-4 (INT4) encoder from buffered vectors, builds the strategy,
     * allocates the off-heap segment, and retroactively encodes all buffered vectors.
     *
     * <p>Uses {@link VasqCalibrator#calibrate4bit} with tighter clipping (2.5σ) for optimal
     * use of the 15 available INT4 quantization levels.</p>
     */
    private synchronized void calibrateVasq4() {
        if (strategy != null) return;
        VasqParams vParams = VasqCalibrator.calibrate4bit(
                calibrationBuffer, calibrationCount, dimensions, vasqSeed);
        Vasq4Encoder enc = new Vasq4Encoder(vParams);
        this.vasq4Encoder = enc;

        this.strategy = new Vasq4Strategy(enc, similarityFunction);
        allocateStorageSegment(capacity, strategy.bytesPerVector());

        log.info("QuantizedHnswIndex VASQ-4 auto-calibrated: {} sample vectors, paddedDim={}, bpv={}",
                calibrationCount, vParams.paddedDim(), strategy.bytesPerVector());

        // Retroactively encode all vectors inserted before calibration
        for (int i = 0; i < nodeCount; i++) {
            if (floatVectors[i] != null) {
                long offset = (long) i * strategy.bytesPerVector();
                strategy.encode(floatVectors[i], storageSegment, offset);
            }
        }

        calibrationBuffer = null;
        calibrationCount = 0;
    }

    private void allocateStorageSegment(int capacity, int bpv) {
        if (this.storageArena != null) {
            this.storageArena.close(); // free previous (shouldn't happen, but defensive)
        }
        this.storageArena = Arena.ofShared();
        this.storageSegment = storageArena.allocate((long) capacity * bpv, 8L);
    }

    // ─────────────── Public accessors ───────────────

    /** Returns the active {@link QuantizationStrategy}, or null if not yet calibrated. */
    public QuantizationStrategy strategy() { return strategy; }

    /** Returns the quantization type used by this index. */
    public QuantizationType quantizationType() { return quantizationType; }

    /** Returns the INT8 quantizer (may be null if not INT8 or not yet calibrated). */
    public ScalarQuantizer quantizer() { return quantizer; }

    /** Returns the non-uniform quantizer (INT4/INT2), or null if INT8/VASQ. */
    public NonUniformQuantizer nonUniformQuantizer() { return nonUniformQuantizer; }

    /** Returns the VASQ encoder, or null if not VASQ or not yet calibrated. */
    public VasqEncoder vasqEncoder() { return vasqEncoder; }

    /** Returns the VASQ-4 encoder, or null if not VASQ-4 or not yet calibrated. */
    public Vasq4Encoder vasq4Encoder() { return vasq4Encoder; }

    /** Returns the configured oversampling factor. */
    public int oversamplingFactor() { return oversamplingFactor; }

    /**
     * Returns true if the quantization strategy has been initialized (either pre-calibrated
     * or auto-calibrated from buffered vectors).
     */
    public boolean isCalibrated() { return strategy != null; }
}
