package com.spectrayan.spector.index.spectrum;

import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.QuantizedHnswIndex;
import com.spectrayan.spector.core.quantization.strategy.VasqStrategy;
import com.spectrayan.spector.core.quantization.vasq.VasqCalibrator;
import com.spectrayan.spector.core.quantization.vasq.VasqParams;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Adaptive per-centroid shard for {@link SpectorIndex}.
 *
 * <h3>Two Modes</h3>
 * <ul>
 *   <li><b>Flat mode</b> (size &lt; {@code shardThreshold}): Stores float32 residuals in
 *       a contiguous {@code float[]} flat buffer. Search is an exhaustive exact scan using
 *       the offset-based {@link SimilarityFunction#compute(float[], int, float[], int, int)}
 *       kernel — no per-vector sub-array extraction.</li>
 *   <li><b>HNSW mode</b> (size ≥ {@code shardThreshold}): A {@link QuantizedHnswIndex} with
 *       per-shard VASQ quantization. The flat buffer is released after promotion.</li>
 * </ul>
 *
 * <h3>Memory Layout (flat mode)</h3>
 * <pre>
 *   flatData[0 .. dim-1]           → residual for vector 0
 *   flatData[dim .. 2*dim-1]       → residual for vector 1
 *   ...
 *   flatData[(n-1)*dim .. n*dim-1] → residual for vector n-1
 * </pre>
 * <p>This layout is cache-friendly: the flat scan reads {@code flatData} sequentially.</p>
 *
 * <h3>Thread Safety (Virtual-Thread Compatible)</h3>
 * <p>SpectorShard is fully thread-safe and designed for high-concurrency workloads,
 * including virtual-thread based parallel search:</p>
 * <ul>
 *   <li><b>Reads ({@link #search})</b>: Multiple concurrent readers are supported via a
 *       {@link ReentrantReadWriteLock}. Post-promotion, a volatile double-check eliminates
 *       lock acquisition entirely for the steady-state search path.</li>
 *   <li><b>Writes ({@link #add})</b>: Serialized via the write-lock. SpectorIndex no longer
 *       needs per-shard external locking; locking is fully internal to this class.</li>
 *   <li><b>Promotion</b>: Promotion holds the write-lock exclusively. In-flight flat scans
 *       complete before promotion runs; searches arriving during promotion block on the
 *       read-lock until promotion finishes and then use the HNSW directly.</li>
 *   <li><b>Virtual threads</b>: {@code ReentrantReadWriteLock} uses {@code LockSupport.park()}
 *       for blocking, which unmounts (not pins) virtual threads. This is correct for Java 21+
 *       virtual thread workloads.</li>
 * </ul>
 *
 * <h3>Promotion Race — Why {@code volatile promoted}?</h3>
 * <p>{@code promoted} is declared {@code volatile} to enable a lock-free fast path in
 * {@link #search}: once {@code promoted} is {@code true} it never reverts, so a stale
 * read of {@code false} is the only case we need to handle conservatively (we enter the
 * read-lock and re-check). The volatile read itself is a single CPU instruction and costs
 * nothing compared to a lock acquisition.</p>
 *
 * <h3>Flat Scan — Zero GC during Search</h3>
 * <p>The flat scan uses an array-based top-K tracker (parallel {@code float[]} scores and
 * {@code int[]} indices) instead of a {@link java.util.PriorityQueue}. No per-candidate
 * object allocation occurs during the scan. Only the final {@link ScoredResult}[] (size k)
 * is allocated once per search to satisfy the public interface.</p>
 */
final class SpectorShard {

    private static final Logger log = LoggerFactory.getLogger(SpectorShard.class);

    /** Initial capacity for flat-mode arrays (number of vectors, not bytes). */
    private static final int INITIAL_FLAT_CAPACITY = 128;

    private final int dimensions;
    private final SpectorIndexConfig config;
    private final float[] centroid;

    // ── Concurrency ────────────────────────────────────────────────────────
    //
    // ReentrantReadWriteLock (not synchronized) avoids virtual thread pinning.
    // Multiple concurrent searches hold readLock simultaneously.
    // Writes (add + promote) hold writeLock exclusively.
    //
    // promoted is volatile for a lock-free fast path in search():
    //   once promoted=true we go directly to hnswIndex.search(), skipping readLock.

    private final ReentrantReadWriteLock rwLock    = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    /**
     * Volatile: enables the post-promotion lock-free fast path in {@link #search}.
     * Written exactly once (false → true) under the write-lock; never reverts.
     */
    private volatile boolean promoted;

    // ── Flat mode (pre-promotion) ──────────────────────────────────────────
    //
    // These fields are only accessed under the write-lock (add/promote/growFlat)
    // or under the read-lock (flatScan). No need for individual volatile declarations
    // — lock acquisition/release establishes the required happens-before edges.

    /**
     * Contiguous flat buffer: {@code flatData[i * dimensions .. (i+1) * dimensions - 1]}
     * holds the float32 residual for vector {@code i}. Null after promotion.
     */
    private float[] flatData;

    /** External string IDs. Null after promotion. */
    private String[] flatIds;

    /** External store indices — {@code int[]}, no boxing. Null after promotion. */
    private int[] flatStoreIndices;

    /** Current allocated capacity (in vectors). */
    private int flatCapacity;

    // ── Shared count ───────────────────────────────────────────────────────

    /** Total vectors in this shard. Accessed only under readLock or writeLock. */
    private int count;

    // ── HNSW mode (post-promotion) ─────────────────────────────────────────

    /** Null until promoted. Set before {@code promoted = true} under writeLock. */
    private QuantizedHnswIndex hnswIndex;

    SpectorShard(int dimensions, SpectorIndexConfig config, float[] centroid) {
        this.dimensions   = dimensions;
        this.config       = config;
        this.centroid     = centroid;
        this.flatCapacity = INITIAL_FLAT_CAPACITY;
        this.flatData         = new float[INITIAL_FLAT_CAPACITY * dimensions];
        this.flatIds          = new String[INITIAL_FLAT_CAPACITY];
        this.flatStoreIndices = new int[INITIAL_FLAT_CAPACITY];
        this.count    = 0;
        this.promoted = false;
    }

    /** Closes the promoted HNSW index (if any), releasing its off-heap Arena. */
    void close() {
        // Safe without lock: called only during index shutdown
        if (promoted && hnswIndex != null) {
            try { hnswIndex.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Adds a residual vector to this shard.
     *
     * <p>Acquires the write-lock internally — callers do not need external synchronization.
     * In flat mode: copies the residual into the contiguous flat buffer, growing it if needed,
     * then triggers promotion if the threshold is reached.
     * In HNSW mode: delegates directly to the live {@link QuantizedHnswIndex}.</p>
     *
     * @param id         external document ID
     * @param storeIndex external store index
     * @param residual   {@code vector − centroid} in float32
     */
    void add(String id, int storeIndex, float[] residual) {
        writeLock.lock();
        try {
            if (promoted) {
                hnswIndex.add(id, storeIndex, residual);
                count++;
            } else {
                if (count >= flatCapacity) {
                    growFlat();
                }
                System.arraycopy(residual, 0, flatData, count * dimensions, dimensions);
                flatIds[count]          = id;
                flatStoreIndices[count] = storeIndex;
                count++;

                if (count >= config.shardThreshold()) {
                    promote();
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Searches for the {@code k} nearest residuals to {@code residualQuery}.
     *
     * <h3>Concurrency Protocol</h3>
     * <ol>
     *   <li><b>Fast path (post-promotion)</b>: reads {@code promoted} (volatile) without
     *       acquiring any shard-level lock. {@code hnswIndex.search()} then uses the HNSW's
     *       own internal read-lock. This path is completely lock-free at the shard level.</li>
     *   <li><b>Slow path (pre-promotion or during promotion)</b>: acquires the read-lock,
     *       re-checks {@code promoted} (promotion may have completed while waiting), and
     *       either delegates to HNSW or performs an exact flat scan. Multiple concurrent
     *       searches in flat mode hold the read-lock simultaneously — full read parallelism.</li>
     * </ol>
     *
     * @param residualQuery {@code query − centroid} in float32
     * @param k             number of results to return
     * @return scored results (IDs + scores), best-first. Empty array if shard is empty.
     */
    ScoredResult[] search(float[] residualQuery, int k) {
        // ── Fast path: volatile read of promoted — lock-free for steady-state searches ──
        if (promoted) {
            // promoted is true and never reverts; hnswIndex is fully constructed
            // (it was set before promoted=true under writeLock, and volatile promoted
            // establishes the happens-before edge, so hnswIndex is visible here).
            return hnswIndex.search(residualQuery, k);
        }

        // ── Slow path: pre-promotion or promotion in flight ──
        // Acquire readLock to safely access flat arrays. Multiple threads may hold
        // readLock concurrently; writeLock (held by promote()) blocks until all
        // in-flight flat scans complete.
        readLock.lock();
        try {
            // Re-check: promotion may have completed while we were waiting for readLock
            if (promoted) {
                return hnswIndex.search(residualQuery, k);
            }
            if (count == 0) return new ScoredResult[0];
            return flatScan(residualQuery, k);
        } finally {
            readLock.unlock();
        }
    }

    /** Returns the approximate number of vectors in this shard. May be slightly stale. */
    int size() {
        // No lock needed for approximate reporting — stale reads are acceptable here
        return count;
    }

    /** Returns whether this shard has been promoted to HNSW mode. */
    boolean isPromoted() {
        return promoted;
    }

    // ── Flat scan (exact similarity) ──────────────────────────────────────

    /**
     * Exhaustive exact similarity scan over the flat float32 residual buffer.
     *
     * <p>Called only while holding the read-lock, so {@code flatData}, {@code flatIds},
     * {@code flatStoreIndices}, and {@code count} are stable for the duration.</p>
     *
     * <p>Uses an array-based top-K tracker instead of a {@link java.util.PriorityQueue}:
     * maintains parallel {@code float[] topScores} and {@code int[] topIndices} arrays.
     * Zero per-candidate object allocation; only the final {@link ScoredResult}[] (size k)
     * is allocated at the end.</p>
     *
     * <p>Uses {@link SimilarityFunction#compute(float[], int, float[], int, int)} to read
     * directly from {@code flatData} with a base offset — no sub-array extraction.</p>
     */
    private ScoredResult[] flatScan(float[] residualQuery, int k) {
        // CRITICAL: Always use L2 for residual search — see SpectorIndex.search() for rationale.
        // L2 is translation-invariant: ‖(q-c)-(x-c)‖ = ‖q-x‖, making scores
        // directly comparable across shards. Cosine/dot are NOT invariant.
        SimilarityFunction fn  = SimilarityFunction.EUCLIDEAN;
        boolean higherIsBetter = false; // L2: lower is better
        int n                  = count;
        int resultCount        = Math.min(k, n);

        // Parallel arrays for top-K tracking — zero GC during the scan
        float[] topScores  = new float[resultCount];
        int[]   topIndices = new int[resultCount];

        float sentinel = higherIsBetter ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        Arrays.fill(topScores, sentinel);
        Arrays.fill(topIndices, -1);

        float worstScore = sentinel;
        int   worstPos   = 0;

        for (int i = 0; i < n; i++) {
            float score = fn.computeForRanking(residualQuery, 0, flatData, i * dimensions, dimensions);

            boolean better = higherIsBetter ? score > worstScore : score < worstScore;
            if (better) {
                topScores[worstPos]  = score;
                topIndices[worstPos] = i;

                // Find the new worst — O(k) scan, negligible vs the O(n) outer loop
                worstScore = topScores[0];
                worstPos   = 0;
                for (int j = 1; j < resultCount; j++) {
                    boolean worse = higherIsBetter
                            ? topScores[j] < worstScore
                            : topScores[j] > worstScore;
                    if (worse) {
                        worstScore = topScores[j];
                        worstPos   = j;
                    }
                }
            }
        }

        // Materialize ScoredResult[] — unavoidable for the public interface
        int validCount = 0;
        for (int i = 0; i < resultCount; i++) {
            if (topIndices[i] >= 0) validCount++;
        }
        ScoredResult[] results = new ScoredResult[validCount];
        int ri = 0;
        for (int i = 0; i < resultCount; i++) {
            int idx = topIndices[i];
            if (idx >= 0) {
                results[ri++] = new ScoredResult(flatIds[idx], flatStoreIndices[idx], topScores[i]);
            }
        }

        if (higherIsBetter) {
            Arrays.sort(results, (a, b) -> Float.compare(b.score(), a.score()));
        } else {
            Arrays.sort(results, (a, b) -> Float.compare(a.score(), b.score()));
        }
        return results;
    }

    // ── Flat buffer growth ────────────────────────────────────────────────

    /** Called only under writeLock. */
    private void growFlat() {
        int newCap = flatCapacity * 2;
        flatData         = Arrays.copyOf(flatData, newCap * dimensions);
        flatIds          = Arrays.copyOf(flatIds, newCap);
        flatStoreIndices = Arrays.copyOf(flatStoreIndices, newCap);
        flatCapacity     = newCap;
    }

    // ── Promotion ─────────────────────────────────────────────────────────

    /**
     * Promotes this shard from flat-scan mode to HNSW mode.
     *
     * <p><b>Called only under the write-lock</b> (from {@link #add}). No concurrent flat scan
     * or add can be in progress. The sequence is:</p>
     * <ol>
     *   <li>Calibrate VASQ from the flat buffer (in-place, no copy).</li>
     *   <li>Build and populate the {@link QuantizedHnswIndex}.</li>
     *   <li>Null the flat buffer arrays to reclaim heap memory.</li>
     *   <li>Write {@code promoted = true} (volatile) — this is the publication fence.
     *       Any thread that subsequently reads {@code promoted=true} is guaranteed to
     *       see the fully constructed {@code hnswIndex} due to the happens-before chain:
     *       <em>writeLock.unlock()</em> → <em>readLock.lock()</em> (for slow-path readers),
     *       and <em>volatile-write promoted</em> → <em>volatile-read promoted</em>
     *       (for fast-path readers).</li>
     * </ol>
     */
    private void promote() {
        int currentSize = count;

        // Step 1: Per-shard VASQ calibration directly on the flat buffer
        VasqParams vasqParams = VasqCalibrator.calibrate(flatData, currentSize, dimensions);
        VasqStrategy vasqStrategy = new VasqStrategy(vasqParams, SimilarityFunction.EUCLIDEAN);

        log.debug("SpectorShard promoting: size={}, paddedDim={}, bpv={}",
                currentSize, vasqParams.paddedDim(), vasqStrategy.bytesPerVector());

        // Step 2: Build HNSW with EUCLIDEAN — residual search must use L2
        // (see SpectorIndex.search() for the full rationale).
        int capacity = currentSize * 4;
        hnswIndex = QuantizedHnswIndex.vasqPreCalibrated(
                dimensions,
                capacity,
                SimilarityFunction.EUCLIDEAN,
                config.hnswParams(),
                vasqStrategy,
                config.oversamplingFactor()
        );

        // Step 3: Bulk-insert all buffered float32 residuals.
        // addOwned() skips the defensive Arrays.copyOf inside storeVector — we are the only
        // owner of flatData and will null it in Step 4, so the reference is safe to transfer.
        // We extract each sub-array with Arrays.copyOfRange (one copy, unavoidable to get an
        // independent float[]) and addOwned() stores it directly — 1 copy total vs 2 with add().
        for (int i = 0; i < currentSize; i++) {
            float[] residual = Arrays.copyOfRange(flatData, i * dimensions, (i + 1) * dimensions);
            hnswIndex.addOwned(flatIds[i], flatStoreIndices[i], residual);
        }

        // Step 4: Null flat arrays to reclaim heap memory
        flatData         = null;
        flatIds          = null;
        flatStoreIndices = null;

        // Step 5: Volatile write — publication fence.
        // After this, any thread reading promoted=true is guaranteed to see
        // the fully constructed hnswIndex (via volatile happens-before).
        promoted = true;
    }
}
