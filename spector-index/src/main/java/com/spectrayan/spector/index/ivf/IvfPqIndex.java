package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.index.NeighborQueue;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.pq.ProductQuantizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * IVF-PQ (Inverted File with Product Quantization) vector index.
 *
 * <p>Combines two techniques for scalable approximate nearest neighbor search:</p>
 * <ol>
 *   <li><b>IVF (Inverted File)</b>: Partitions the vector space into {@code nlist}
 *       Voronoi cells via K-Means. At query time, only the {@code nprobe} nearest
 *       cells are scanned — reducing the search space by {@code nlist/nprobe}.</li>
 *   <li><b>PQ (Product Quantization)</b>: Compresses each vector from
 *       {@code dims × 4} bytes to {@code M} bytes using trained codebooks.
 *       Distance computation uses ADC (Asymmetric Distance Computation) —
 *       a precomputed lookup table eliminates the need to decompress vectors.</li>
 * </ol>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Training</b>: Call {@link #train(float[][])} with a representative sample
 *       to learn cluster centroids and PQ codebooks.</li>
 *   <li><b>Indexing</b>: Call {@link #add(String, int, float[])} for each vector.
 *       Vectors are assigned to clusters and PQ-compressed.</li>
 *   <li><b>Search</b>: Call {@link #search(float[], int)} for ANN queries.</li>
 * </ol>
 *
 * <h3>Memory</h3>
 * <p>At M=16 subspaces: 1M vectors × 128 dims = ~16 MB (vs 512 MB float32).</p>
 *
 * @see ProductQuantizer
 */
public class IvfPqIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(IvfPqIndex.class);

    private final int dimensions;
    private final int nlist;          // number of clusters
    private final int nprobe;         // clusters to search at query time
    private final int numSubspaces;   // PQ M parameter
    private final SimilarityFunction similarityFunction;

    // ── Trained state ──
    private volatile boolean trained;
    private float[][] centroids;      // [nlist][dims] — cluster centroids
    private ProductQuantizer pq;      // PQ codebook

    // ── Index data ──
    private final List<PostingList> postingLists;  // per-cluster posting lists
    private volatile int totalVectors;

    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates an IVF-PQ index.
     *
     * @param dimensions         vector dimensionality
     * @param nlist              number of IVF clusters (recommended: √N to 4√N)
     * @param nprobe             clusters to probe during search (higher = better recall)
     * @param numSubspaces       PQ subspaces M (must divide dimensions evenly)
     * @param similarityFunction distance metric
     */
    public IvfPqIndex(int dimensions, int nlist, int nprobe, int numSubspaces,
                       SimilarityFunction similarityFunction) {
        if (dimensions % numSubspaces != 0) {
            throw new IllegalArgumentException(
                    "dimensions (" + dimensions + ") must be divisible by numSubspaces (" + numSubspaces + ")");
        }
        this.dimensions = dimensions;
        this.nlist = nlist;
        this.nprobe = nprobe;
        this.numSubspaces = numSubspaces;
        this.similarityFunction = similarityFunction;
        this.trained = false;
        this.totalVectors = 0;

        // Initialize empty posting lists
        this.postingLists = new ArrayList<>(nlist);
        for (int i = 0; i < nlist; i++) {
            postingLists.add(new PostingList());
        }

        log.info("IvfPqIndex created: dims={}, nlist={}, nprobe={}, M={}",
                dimensions, nlist, nprobe, numSubspaces);
    }

    /**
     * Convenience constructor with sensible defaults.
     *
     * @param dimensions vector dimensionality
     * @param expectedSize expected number of vectors (used to compute nlist)
     */
    public IvfPqIndex(int dimensions, int expectedSize) {
        this(dimensions,
                Math.max(16, (int) Math.sqrt(expectedSize)),  // nlist = √N
                10,                                            // nprobe
                Math.max(4, dimensions / 8),                   // M = dims/8
                SimilarityFunction.COSINE);
    }

    /**
     * Trains the IVF-PQ index from a representative sample of vectors.
     *
     * <p>This step learns:</p>
     * <ol>
     *   <li>Cluster centroids via K-Means (for the IVF partitioning)</li>
     *   <li>PQ codebooks via per-subspace K-Means (for compression)</li>
     * </ol>
     *
     * <p>Training should use at least {@code nlist × 40} vectors for good results.
     * More samples = better cluster quality = higher recall.</p>
     *
     * @param samples training vectors
     */
    public void train(float[][] samples) {
        if (samples.length < nlist) {
            throw new IllegalArgumentException(
                    "Need at least nlist (" + nlist + ") samples, got " + samples.length);
        }

        log.info("Training IVF-PQ: {} samples, nlist={}, M={}", samples.length, nlist, numSubspaces);
        long start = System.nanoTime();

        // Step 1: Train IVF centroids via K-Means
        this.centroids = trainCentroids(samples);

        // Step 2: Compute residuals (vector - nearest centroid)
        // PQ is trained on residuals for better accuracy
        float[][] residuals = new float[samples.length][dimensions];
        for (int i = 0; i < samples.length; i++) {
            int cluster = nearestCentroid(samples[i]);
            for (int d = 0; d < dimensions; d++) {
                residuals[i][d] = samples[i][d] - centroids[cluster][d];
            }
        }

        // Step 3: Train PQ codebooks on residuals
        this.pq = ProductQuantizer.train(residuals, dimensions, numSubspaces);

        this.trained = true;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("IVF-PQ training complete in {}ms", elapsedMs);
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
            // Assign to nearest cluster
            int cluster = nearestCentroid(vector);

            // Compute residual and PQ-encode
            float[] residual = new float[dimensions];
            for (int d = 0; d < dimensions; d++) {
                residual[d] = vector[d] - centroids[cluster][d];
            }
            byte[] code = pq.encode(residual);

            // Add to posting list
            postingLists.get(cluster).add(id, storeIndex, code);
            totalVectors++;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public ScoredResult[] search(float[] query, int k) {
        if (!trained) {
            throw new IllegalStateException("Index must be trained before searching.");
        }
        if (query.length != dimensions) {
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + query.length);
        }
        if (totalVectors == 0) {
            return new ScoredResult[0];
        }

        // Step 1: Find the nprobe nearest cluster centroids
        int[] probeClusters = findNearestClusters(query, nprobe);

        // Step 2: Collect all candidates from probed clusters with ADC distances
        List<ScoredResult> candidates = new ArrayList<>();

        for (int clusterIdx : probeClusters) {
            PostingList plist = postingLists.get(clusterIdx);
            if (plist.size() == 0) continue;

            // Compute residual query for this cluster
            float[] residualQuery = new float[dimensions];
            for (int d = 0; d < dimensions; d++) {
                residualQuery[d] = query[d] - centroids[clusterIdx][d];
            }

            // Precompute ADC distance table for this cluster's residual query
            float[][] distTable = pq.computeDistanceTable(residualQuery);

            // Scan all codes in this posting list
            int size = plist.size();
            byte[][] codes = plist.codes();
            String[] ids = plist.ids();
            int[] indices = plist.storeIndices();

            for (int i = 0; i < size; i++) {
                float dist = ProductQuantizer.adcDistance(distTable, codes[i]);
                // Convert L2 distance to similarity score (lower dist = higher similarity)
                float score = 1.0f / (1.0f + dist);
                candidates.add(new ScoredResult(ids[i], indices[i], score));
            }
        }

        // Step 3: Sort by score descending (highest similarity first)
        candidates.sort(java.util.Comparator.naturalOrder()); // ScoredResult.compareTo is descending

        // Return top-k
        int resultCount = Math.min(k, candidates.size());
        return candidates.subList(0, resultCount).toArray(ScoredResult[]::new);
    }

    @Override
    public int size() { return totalVectors; }

    @Override
    public SimilarityFunction similarityFunction() { return similarityFunction; }

    @Override
    public void close() {
        // No external resources
    }

    /** Returns true if the index has been trained. */
    public boolean isTrained() { return trained; }

    /** Returns the number of clusters. */
    public int nlist() { return nlist; }

    /** Returns the number of probed clusters during search. */
    public int nprobe() { return nprobe; }

    /** Returns the product quantizer (null if not trained). */
    public ProductQuantizer quantizer() { return pq; }

    // ─────────────── IVF K-Means training ───────────────

    private float[][] trainCentroids(float[][] samples) {
        int n = samples.length;
        float[][] centers = new float[nlist][dimensions];
        java.util.Random rng = new java.util.Random(42);

        // K-Means++ initialization
        System.arraycopy(samples[rng.nextInt(n)], 0, centers[0], 0, dimensions);
        float[] minDists = new float[n];
        Arrays.fill(minDists, Float.MAX_VALUE);

        for (int c = 1; c < nlist; c++) {
            double totalDist = 0;
            for (int i = 0; i < n; i++) {
                float d = squaredL2(samples[i], centers[c - 1]);
                if (d < minDists[i]) minDists[i] = d;
                totalDist += minDists[i];
            }
            double target = rng.nextDouble() * totalDist;
            double cumulative = 0;
            int selected = 0;
            for (int i = 0; i < n; i++) {
                cumulative += minDists[i];
                if (cumulative >= target) { selected = i; break; }
            }
            System.arraycopy(samples[selected], 0, centers[c], 0, dimensions);
        }

        // K-Means iterations
        int[] assignments = new int[n];
        for (int iter = 0; iter < 25; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int nearest = nearestCentroidIdx(samples[i], centers);
                if (nearest != assignments[i]) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }
            if (!changed) break;

            float[][] newCenters = new float[nlist][dimensions];
            int[] counts = new int[nlist];
            for (int i = 0; i < n; i++) {
                counts[assignments[i]]++;
                for (int d = 0; d < dimensions; d++) {
                    newCenters[assignments[i]][d] += samples[i][d];
                }
            }
            for (int c = 0; c < nlist; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dimensions; d++) {
                        newCenters[c][d] /= counts[c];
                    }
                    centers[c] = newCenters[c];
                }
            }
        }

        return centers;
    }

    // ─────────────── Helpers ───────────────

    private int nearestCentroid(float[] vector) {
        return nearestCentroidIdx(vector, centroids);
    }

    private static int nearestCentroidIdx(float[] vector, float[][] centroids) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int k = 0; k < centroids.length; k++) {
            float dist = squaredL2(vector, centroids[k]);
            if (dist < bestDist) {
                bestDist = dist;
                best = k;
            }
        }
        return best;
    }

    private int[] findNearestClusters(float[] query, int probe) {
        int actualProbe = Math.min(probe, nlist);
        // Simple: compute distances to all centroids, pick top-nprobe
        float[] dists = new float[nlist];
        for (int c = 0; c < nlist; c++) {
            dists[c] = squaredL2(query, centroids[c]);
        }

        // Partial sort to find top-nprobe nearest
        Integer[] indices = new Integer[nlist];
        for (int i = 0; i < nlist; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Float.compare(dists[a], dists[b]));

        int[] result = new int[actualProbe];
        for (int i = 0; i < actualProbe; i++) {
            result[i] = indices[i];
        }
        return result;
    }

    private String findIdByStoreIndex(int storeIndex) {
        for (PostingList plist : postingLists) {
            String id = plist.findId(storeIndex);
            if (id != null) return id;
        }
        return null;
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
