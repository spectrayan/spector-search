package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

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

    private final int dimensions;
    private final SimilarityFunction similarityFunction;

    // ── Trained state ──
    private volatile boolean trained;
    private int numCells;
    private float[][] centroids;  // [numCells][dimensions]

    // ── Index data ──
    private List<FlatPostingList> postingLists;
    private volatile int totalVectors;

    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates an IVF-Flat index.
     *
     * @param dimensions         vector dimensionality
     * @param similarityFunction distance metric
     */
    public IvfFlatIndex(int dimensions, SimilarityFunction similarityFunction) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive, got " + dimensions);
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
     * @throws IllegalArgumentException if numCells is out of range or training set is too small
     * @throws IllegalStateException    if the index has already been trained
     */
    public void train(float[][] trainingVectors, int numCells) {
        if (trained) {
            throw new IllegalStateException("Index has already been trained.");
        }
        if (numCells < MIN_CELLS || numCells > MAX_CELLS) {
            throw new IllegalArgumentException(
                    "numCells must be between " + MIN_CELLS + " and " + MAX_CELLS + ", got " + numCells);
        }
        if (trainingVectors == null || trainingVectors.length < numCells) {
            int provided = (trainingVectors == null) ? 0 : trainingVectors.length;
            throw new IllegalArgumentException(
                    "Training requires at least " + numCells + " vectors (the configured number of cells), "
                            + "but only " + provided + " were provided.");
        }

        log.info("Training IVF-Flat: {} samples, numCells={}", trainingVectors.length, numCells);
        long start = System.nanoTime();

        this.numCells = numCells;
        this.centroids = trainCentroids(trainingVectors, numCells);

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
            throw new IllegalStateException("Index must be trained before adding vectors. Call train() first.");
        }
        if (vector.length != dimensions) {
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + vector.length);
        }

        writeLock.lock();
        try {
            int cell = nearestCentroid(vector);
            postingLists.get(cell).add(id, storeIndex, vector);
            totalVectors++;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Searches the index probing the {@code nprobe} nearest cells.
     *
     * @param query  the query vector
     * @param nprobe number of cells to probe (1 to numCells)
     * @param topK   number of results to return
     * @return scored results sorted by relevance
     * @throws IllegalStateException    if the index is not trained
     * @throws IllegalArgumentException if nprobe is invalid
     */
    public ScoredResult[] search(float[] query, int nprobe, int topK) {
        if (!trained) {
            throw new IllegalStateException("Index must be trained before searching. Call train() first.");
        }
        if (query.length != dimensions) {
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + query.length);
        }
        if (nprobe < 1 || nprobe > numCells) {
            throw new IllegalArgumentException(
                    "nprobe must be between 1 and " + numCells + ", got " + nprobe);
        }
        if (totalVectors == 0) {
            return new ScoredResult[0];
        }

        // Find the nprobe nearest centroids
        int[] probeCells = findNearestCentroids(query, nprobe);

        // Exhaustive scan within probed cells using exact distance
        List<ScoredResult> candidates = new ArrayList<>();
        for (int cellIdx : probeCells) {
            FlatPostingList plist = postingLists.get(cellIdx);
            int size = plist.size();
            if (size == 0) continue;

            String[] ids = plist.ids();
            int[] indices = plist.storeIndices();
            float[][] vectors = plist.vectors();

            for (int i = 0; i < size; i++) {
                float score = similarityFunction.compute(query, vectors[i]);
                // For distance metrics (lower is better), convert to a similarity score
                if (!similarityFunction.higherIsBetter()) {
                    score = 1.0f / (1.0f + score);
                }
                candidates.add(new ScoredResult(ids[i], indices[i], score));
            }
        }

        // Sort descending by score
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

    // ─────────────── K-Means Training ───────────────

    private float[][] trainCentroids(float[][] samples, int k) {
        int n = samples.length;
        float[][] centers = new float[k][dimensions];
        java.util.Random rng = new java.util.Random(42);

        // K-Means++ initialization
        System.arraycopy(samples[rng.nextInt(n)], 0, centers[0], 0, dimensions);
        float[] minDists = new float[n];
        Arrays.fill(minDists, Float.MAX_VALUE);

        for (int c = 1; c < k; c++) {
            double totalDist = 0;
            for (int i = 0; i < n; i++) {
                float d = squaredL2(samples[i], centers[c - 1]);
                if (d < minDists[i]) {
                    minDists[i] = d;
                }
                totalDist += minDists[i];
            }
            double target = rng.nextDouble() * totalDist;
            double cumulative = 0;
            int selected = 0;
            for (int i = 0; i < n; i++) {
                cumulative += minDists[i];
                if (cumulative >= target) {
                    selected = i;
                    break;
                }
            }
            System.arraycopy(samples[selected], 0, centers[c], 0, dimensions);
        }

        // K-Means iterations
        int[] assignments = new int[n];
        for (int iter = 0; iter < KMEANS_MAX_ITERATIONS; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int nearest = nearestCentroidIdx(samples[i], centers, k);
                if (nearest != assignments[i]) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }
            if (!changed) break;

            // Recompute centroids
            float[][] newCenters = new float[k][dimensions];
            int[] counts = new int[k];
            for (int i = 0; i < n; i++) {
                counts[assignments[i]]++;
                for (int d = 0; d < dimensions; d++) {
                    newCenters[assignments[i]][d] += samples[i][d];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dimensions; d++) {
                        newCenters[c][d] /= counts[c];
                    }
                    centers[c] = newCenters[c];
                }
                // If a cluster is empty, keep its previous centroid
            }
        }

        return centers;
    }

    // ─────────────── Helpers ───────────────

    private int nearestCentroid(float[] vector) {
        return nearestCentroidIdx(vector, centroids, numCells);
    }

    private static int nearestCentroidIdx(float[] vector, float[][] centroids, int k) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int c = 0; c < k; c++) {
            float dist = squaredL2(vector, centroids[c]);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private int[] findNearestCentroids(float[] query, int nprobe) {
        int actualProbe = Math.min(nprobe, numCells);
        float[] dists = new float[numCells];
        for (int c = 0; c < numCells; c++) {
            dists[c] = squaredL2(query, centroids[c]);
        }

        // Partial sort: find top-nprobe nearest
        Integer[] indices = new Integer[numCells];
        for (int i = 0; i < numCells; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, (a, b) -> Float.compare(dists[a], dists[b]));

        int[] result = new int[actualProbe];
        for (int i = 0; i < actualProbe; i++) {
            result[i] = indices[i];
        }
        return result;
    }

    private static float squaredL2(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
