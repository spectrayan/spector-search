/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.index.ivf;

import com.spectrayan.spector.core.cluster.KMeans;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.NeighborQueue;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.pq.ProductQuantizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

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

    // ── Concurrency: StampedLock ──
    // Optimistic read (lock-free) for searches; exclusive writeLock for adds.
    // VT-safe: readLock fallback uses LockSupport.park(), never pins virtual threads.
    private final StampedLock stampedLock = new StampedLock();

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
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "dimensions (" + dimensions + ") must be divisible by numSubspaces (" + numSubspaces + ")");
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
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Need at least nlist (" + nlist + ") samples, got " + samples.length);
        }

        log.info("Training IVF-PQ: {} samples, nlist={}, M={}", samples.length, nlist, numSubspaces);
        long start = System.nanoTime();

        // Step 1: Train IVF centroids via K-Means
        this.centroids = KMeans.train(samples, nlist, 25, 42L);

        // Step 2: Compute residuals (vector - nearest centroid)
        // PQ is trained on residuals for better accuracy
        float[][] residuals = new float[samples.length][dimensions];
        for (int i = 0; i < samples.length; i++) {
            int cluster = KMeans.nearestCentroid(samples[i], centroids);
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
            throw new SpectorInternalException(ErrorCode.INDEX_NOT_TRAINED);
        }
        if (vector.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, vector.length);
        }

        long stamp = stampedLock.writeLock();
        try {
            // Assign to nearest cluster
            int cluster = KMeans.nearestCentroid(vector, centroids);

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
            stampedLock.unlockWrite(stamp);
        }
    }

    @Override
    public ScoredResult[] search(float[] query, int k) {
        if (!trained) {
            throw new SpectorInternalException(ErrorCode.INDEX_NOT_TRAINED);
        }
        if (query.length != dimensions) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, dimensions, query.length);
        }
        if (totalVectors == 0) {
            return new ScoredResult[0];
        }

        // ── Optimistic read — lock-free fast path ──
        long stamp = stampedLock.tryOptimisticRead();
        ScoredResult[] result = trySearchOptimistic(query, k, stamp);
        if (result != null) return result;

        // ── Fallback to shared readLock (concurrent add detected) ──
        stamp = stampedLock.readLock();
        try {
            return doSearch(query, k);
        } finally {
            stampedLock.unlockRead(stamp);
        }
    }

    /**
     * Lock-free optimistic search attempt.
     * Returns null if a concurrent write was detected; caller retries under readLock.
     */
    private ScoredResult[] trySearchOptimistic(float[] query, int k, long stamp) {
        int tv = totalVectors;
        if (!stampedLock.validate(stamp)) return null;
        if (tv == 0) return new ScoredResult[0];

        ScoredResult[] result = doSearch(query, k);
        return stampedLock.validate(stamp) ? result : null;
    }

    /** Core search logic — safe to call under optimistic stamp or readLock. */
    private ScoredResult[] doSearch(float[] query, int k) {
        if (totalVectors == 0) return new ScoredResult[0];

        // Step 1: Find the nprobe nearest cluster centroids
        int[] probeClusters = KMeans.nearestCentroids(query, centroids, nprobe);

        // Step 2: Collect all candidates from probed clusters with ADC distances
        List<ScoredResult> candidates = new ArrayList<>();

        for (int clusterIdx : probeClusters) {
            PostingList plist = postingLists.get(clusterIdx);
            if (plist.size() == 0) continue;

            // Snapshot array references — stable even if a concurrent grow() swaps arrays
            float[] residualQuery = new float[dimensions];
            for (int d = 0; d < dimensions; d++) {
                residualQuery[d] = query[d] - centroids[clusterIdx][d];
            }

            float[][] distTable = pq.computeDistanceTable(residualQuery);

            int       size    = plist.size();
            byte[][]  codes   = plist.codes();
            String[]  ids     = plist.ids();
            int[]     indices = plist.storeIndices();

            for (int i = 0; i < size; i++) {
                float dist  = ProductQuantizer.adcDistance(distTable, codes[i]);
                float score = 1.0f / (1.0f + dist);
                candidates.add(new ScoredResult(ids[i], indices[i], score));
            }
        }

        // Step 3: Sort by score descending and return top-k
        candidates.sort(java.util.Comparator.naturalOrder());
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

    private static float squaredL2(float[] a, float[] b) {
        return KMeans.squaredL2(a, b);
    }
}