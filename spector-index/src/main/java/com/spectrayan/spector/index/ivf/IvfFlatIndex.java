package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.core.cluster.KMeans;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * IVF-Flat (Inverted File with exact distance) vector index.
 *
 * <p>Partitions the vector space into Voronoi cells via K-Means clustering.
 * At query time, only the {@code nprobe} nearest cells are exhaustively scanned
 * using exact distance computation (SIMD-accelerated via the SimilarityFunction kernels).</p>
 *
 * <p>Unlike {@link IvfPqIndex}, this index stores raw float vectors without compression,
 * providing exact distance results at the cost of higher memory usage.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Training</b>: Call {@link #train(float[][], int)} with a representative sample
 *       to learn cluster centroids.</li>
 *   <li><b>Indexing</b>: Call {@link #add(String, int, float[])} for each vector.
 *       Vectors are assigned to their nearest centroid.</li>
 *   <li><b>Search</b>: Call {@link #search(float[], int, int)} with configurable nprobe.</li>
 * </ol>
 *
 * @see IvfPqIndex
 */
public class IvfFlatIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(IvfFlatIndex.class);

    /** Minimum allowed number of cells. */
    public static final int MIN_CELLS = 2;

    /** Maximum allowed number of cells. */
    public static final int MAX_CELLS = 65_536;

    private static final int KMEANS_MAX_ITERATIONS = 25;
    private static final long KMEANS_SEED = 42L;

    private final int dimensions;
    private final SimilarityFunction similarityFunction;

    // ── Trained state ──
    private volatile boolean trained;
    private int numCells;
    private float[][] centroids;  // [numCells][dimensions]

    // ── Index data ──
    private List<FlatPostingList> postingLists;
    private volatile int totalVectors;

    // ── Concurrency ──
    //
    // StampedLock provides three modes:
    //   writeLock()         — exclusive, used by add()
    //   readLock()          — shared, used as search() fallback
    //   tryOptimisticRead() — lock-free! used as search() fast path
    //
    // In a read-dominant search workload (searches >> adds), the optimistic read
    // succeeds on nearly every call because there is no concurrent writer.
    // Cost: 2 CPU instructions (read stamp + validate stamp). Zero atomic ops.
    // If a write races, validate() returns false and we fall back to readLock().
    //
    // VT note: StampedLock.readLock() uses LockSupport.park() — VTs unmount, not pin.
    private final StampedLock stampedLock = new StampedLock();

    /**
     * Creates an IVF-Flat index.
     *
     * @param dimensions         vector dimensionality
     * @param similarityFunction distance metric
     */
    public IvfFlatIndex(int dimensions, SimilarityFunction similarityFunction) {
        if (dimensions <= 0) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_INVALID, dimensions);
        }
        this.dimensions = dimensions;
        this.similarityFunction = similarityFunction;
        this.trained = false;
        this.totalVectors = 0;
    }

    /**
     * Trains the IVF-Flat index by running K-Means clustering on the provided vectors.
     *
     * @param trainingVectors representative training vectors
     * @param numCells        number of Voronoi cells (partitions), must be between
     *                        {@link #MIN_CELLS} and {@link #MAX_CELLS}
     * @throws SpectorValidationException if numCells is out of range or training set is too small
     * @throws SpectorValidationException    if the index has already been trained
     */
    public void train(float[][] trainingVectors, int numCells) {
        if (trained) {
            throw new SpectorInternalException(ErrorCode.INVARIANT_VIOLATED, "Index already trained");
        }
        if (numCells < MIN_CELLS || numCells > MAX_CELLS) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "numCells", MIN_CELLS, MAX_CELLS, numCells);
        }
        if (trainingVectors == null || trainingVectors.length < numCells) {
            int provided = (trainingVectors == null) ? 0 : trainingVectors.length;
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Training requires at least " + numCells + " vectors (the configured number of cells), " + "but only " + provided + " were provided.");
        }

        log.info("Training IVF-Flat: {} samples, numCells={}", trainingVectors.length, numCells);
        long start = System.nanoTime();

        this.numCells = numCells;
        this.centroids = KMeans.train(trainingVectors, numCells, KMEANS_MAX_ITERATIONS, KMEANS_SEED);

        // Initialize posting lists
        this.postingLists = new ArrayList<>(numCells);
        for (int i = 0; i < numCells; i++) {
            postingLists.add(new FlatPostingList());
        }

        this.trained = true;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("IVF-Flat training complete in {}ms", elapsedMs);
    }

    @Override
    public void add(String id, int storeIndex, float[] vector) {
        if (!trained) {
            throw new SpectorInternalException(ErrorCode.INDEX_NOT_TRAINED);
        }
        if (vector.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, vector.length);
        }

        long stamp = stampedLock.writeLock();
        try {
            int cell = KMeans.nearestCentroid(vector, centroids);
            postingLists.get(cell).add(id, storeIndex, vector);
            totalVectors++;
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    /**
     * Searches the index probing the {@code nprobe} nearest cells.
     *
     * @param query  the query vector
     * @param nprobe number of cells to probe (1 to numCells)
     * @param topK   number of results to return
     * @return scored results sorted by relevance
     * @throws SpectorValidationException    if the index is not trained
     * @throws SpectorValidationException if nprobe is invalid
     */
    public ScoredResult[] search(float[] query, int nprobe, int topK) {
        if (!trained) {
            throw new SpectorInternalException(ErrorCode.INDEX_NOT_TRAINED);
        }
        if (query.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, query.length);
        }
        if (nprobe < 1 || nprobe > numCells) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "nprobe", 1, numCells, nprobe);
        }
        if (totalVectors == 0) {
            return new ScoredResult[0];
        }

        // ── Optimistic read (lock-free fast path for read-dominant workloads) ──
        //
        // tryOptimisticRead() returns a stamp without acquiring any lock.
        // We read all shared data under this stamp, then validate it.
        // If no write occurred during our read, validate() returns true and we're done.
        // If a write raced us, validate() returns false and we fall back to readLock().
        //
        // Key: local variables snapshot the array references and size before reading
        // elements. Even if a grow() replaces the backing arrays during our read,
        // our local references still point to the old (valid) arrays which contain
        // consistent data for all indices [0, localSize).
        long stamp = stampedLock.tryOptimisticRead();
        ScoredResult[] result = trySearchOptimistic(query, nprobe, topK, stamp);
        if (result != null) return result;

        // ── Fallback: shared readLock (rare — only when a concurrent add() races) ──
        stamp = stampedLock.readLock();
        try {
            return doSearch(query, nprobe, topK);
        } finally {
            stampedLock.unlockRead(stamp);
        }
    }

    /**
     * Attempts an optimistic (lock-free) search.
     * Returns null if a concurrent write was detected, signalling the caller to retry
     * under a readLock.
     */
    private ScoredResult[] trySearchOptimistic(float[] query, int nprobe, int topK, long stamp) {
        // Snapshot mutable state under the optimistic stamp
        int tv = totalVectors;
        if (!stampedLock.validate(stamp)) return null;
        if (tv == 0) return new ScoredResult[0];

        ScoredResult[] result = doSearch(query, nprobe, topK);

        // Validate: if any write happened during doSearch(), result may be stale
        // (but not corrupted — local references are stable). We return it if valid;
        // if invalid, caller retries under readLock for a strictly consistent view.
        return stampedLock.validate(stamp) ? result : null;
    }

    /** Core search logic — called under either optimistic stamp or readLock. */
    private ScoredResult[] doSearch(float[] query, int nprobe, int topK) {
        if (totalVectors == 0) return new ScoredResult[0];

        // Find the nprobe nearest centroids
        int[] probeCells = KMeans.nearestCentroids(query, centroids, nprobe);

        // Exhaustive scan within probed cells using exact distance
        List<ScoredResult> candidates = new ArrayList<>();
        for (int cellIdx : probeCells) {
            FlatPostingList plist = postingLists.get(cellIdx);
            int size = plist.size();
            if (size == 0) continue;

            // Snapshot array references locally — stable even if grow() swaps arrays
            String[]   ids     = plist.ids();
            int[]      indices = plist.storeIndices();
            float[][]  vectors = plist.vectors();

            for (int i = 0; i < size; i++) {
                float score = similarityFunction.compute(query, vectors[i]);
                if (!similarityFunction.higherIsBetter()) {
                    score = 1.0f / (1.0f + score);
                }
                candidates.add(new ScoredResult(ids[i], indices[i], score));
            }
        }

        candidates.sort(null); // ScoredResult.compareTo is descending
        int resultCount = Math.min(topK, candidates.size());
        return candidates.subList(0, resultCount).toArray(ScoredResult[]::new);
    }

    /**
     * Searches using default nprobe (min(10, numCells)).
     */
    @Override
    public ScoredResult[] search(float[] query, int k) {
        int defaultNprobe = Math.min(10, numCells);
        return search(query, defaultNprobe, k);
    }

    @Override
    public int size() {
        return totalVectors;
    }

    @Override
    public SimilarityFunction similarityFunction() {
        return similarityFunction;
    }

    @Override
    public void close() {
        // No external resources to release
    }

    /** Returns true if the index has been trained. */
    public boolean isTrained() {
        return trained;
    }

    /** Returns the number of cells (clusters). */
    public int numCells() {
        return numCells;
    }

    /** Returns the vector dimensionality. */
    public int dimensions() {
        return dimensions;
    }

}