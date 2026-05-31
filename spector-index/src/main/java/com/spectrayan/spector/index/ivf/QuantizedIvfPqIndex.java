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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.StampedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.quantization.CrumbPacker;
import com.spectrayan.spector.core.quantization.NibblePacker;
import com.spectrayan.spector.core.quantization.NonUniformQuantizer;
import com.spectrayan.spector.core.similarity.PackedDotProduct;
import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.pq.ProductQuantizer;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * IVF-PQ vector index with INT4/INT2 quantization support and configurable rescore strategy.
 *
 * <p>Extends the standard IVF-PQ approach with packed quantized storage for the coarse
 * quantizer (centroid distances) and residual vectors. Supports three quantization modes:</p>
 * <ul>
 *   <li><b>INT8</b> — standard PQ encoding (unchanged behavior)</li>
 *   <li><b>INT4</b> — nibble-packed residuals with non-uniform quantization (8× compression)</li>
 *   <li><b>INT2</b> — crumb-packed residuals with non-uniform quantization (16× compression)</li>
 * </ul>
 *
 * <h3>Rescore Strategy</h3>
 * <p>When the oversampling factor is greater than 1, the index retrieves
 * {@code oversamplingFactor × k} candidates using fast quantized distance,
 * then rescores them with exact float32 distances to return the true top-K.</p>
 *
 * @see IvfPqIndex
 * @see PackedDotProduct
 * @see NonUniformQuantizer
 */
public class QuantizedIvfPqIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(QuantizedIvfPqIndex.class);

    private final int dimensions;
    private final int nlist;
    private final int nprobe;
    private final int numSubspaces;
    private final SimilarityFunction similarityFunction;
    private final QuantizationType quantizationType;
    private final NonUniformQuantizer nonUniformQuantizer;
    private final int oversamplingFactor;

    // ── Global centroids for PackedDotProduct ──
    private final float[] globalCentroids;

    // ── Trained state ──
    private volatile boolean trained;
    private float[][] centroids;              // [nlist][dims] — cluster centroids
    private byte[][] packedCentroids;         // [nlist][packedSize] — packed cluster centroids (INT4/INT2)
    private ProductQuantizer pq;              // PQ codebook (used for INT8 fallback)

    // ── Index data ──
    private final List<PostingList> postingLists;
    private final List<float[]> floatVectors;     // full-precision vectors for rescore
    private final List<String> vectorIds;         // document IDs indexed by insert order
    private volatile int totalVectors;

    // ── Concurrency: StampedLock ──
    // Optimistic read (lock-free) for searches; exclusive writeLock for adds.
    // floatVectors (ArrayList) and postingLists are safely readable under the optimistic
    // stamp as long as we access only indices < totalVectors (written before count increment).
    // VT-safe: readLock fallback uses LockSupport.park(), never pins virtual threads.
    private final StampedLock stampedLock = new StampedLock();

    /**
     * Creates a quantized IVF-PQ index with INT4/INT2 support and configurable rescore.
     *
     * @param dimensions           vector dimensionality
     * @param nlist                number of IVF clusters
     * @param nprobe               clusters to probe during search
     * @param numSubspaces         PQ subspaces M (must divide dimensions evenly)
     * @param similarityFunction   distance metric
     * @param quantizationType     quantization type (SCALAR_INT8, SCALAR_INT4, or SCALAR_INT2)
     * @param nonUniformQuantizer  calibrated non-uniform quantizer (required for INT4/INT2, null for INT8)
     * @param oversamplingFactor   rescore oversampling factor (1 = no rescore)
     */
    public QuantizedIvfPqIndex(int dimensions, int nlist, int nprobe, int numSubspaces,
                                SimilarityFunction similarityFunction,
                                QuantizationType quantizationType,
                                NonUniformQuantizer nonUniformQuantizer,
                                int oversamplingFactor) {
        if (dimensions % numSubspaces != 0) {
            throw new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, "dimensions (" + dimensions + ") must be divisible by numSubspaces (" + numSubspaces + ")");
        }
        if (quantizationType == QuantizationType.SCALAR_INT4 || quantizationType == QuantizationType.SCALAR_INT2) {
            if (nonUniformQuantizer == null) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "NonUniformQuantizer (required for " + quantizationType + ")");
            }
        }

        this.dimensions = dimensions;
        this.nlist = nlist;
        this.nprobe = nprobe;
        this.numSubspaces = numSubspaces;
        this.similarityFunction = similarityFunction;
        this.quantizationType = quantizationType != null ? quantizationType : QuantizationType.SCALAR_INT8;
        this.nonUniformQuantizer = nonUniformQuantizer;
        this.oversamplingFactor = Math.max(1, oversamplingFactor);
        this.trained = false;
        this.totalVectors = 0;

        // Compute global centroids for PackedDotProduct
        if ((this.quantizationType == QuantizationType.SCALAR_INT4
                || this.quantizationType == QuantizationType.SCALAR_INT2)
                && nonUniformQuantizer != null) {
            this.globalCentroids = computeGlobalCentroids(nonUniformQuantizer);
        } else {
            this.globalCentroids = null;
        }

        // Initialize posting lists
        this.postingLists = new ArrayList<>(nlist);
        for (int i = 0; i < nlist; i++) {
            postingLists.add(new PostingList());
        }

        // Float vectors stored for rescore
        this.floatVectors = new ArrayList<>();
        this.vectorIds = new ArrayList<>();

        log.info("QuantizedIvfPqIndex created: dims={}, nlist={}, nprobe={}, M={}, type={}, oversampling={}",
                dimensions, nlist, nprobe, numSubspaces, this.quantizationType, this.oversamplingFactor);
    }

    /**
     * Convenience constructor for INT8 mode (backward-compatible behavior).
     */
    public QuantizedIvfPqIndex(int dimensions, int nlist, int nprobe, int numSubspaces,
                                SimilarityFunction similarityFunction) {
        this(dimensions, nlist, nprobe, numSubspaces, similarityFunction,
                QuantizationType.SCALAR_INT8, null, 1);
    }

    /**
     * Trains the IVF-PQ index from a representative sample of vectors.
     *
     * <p>For INT4/INT2 modes, cluster centroids are stored in packed format for fast
     * coarse quantizer distance computation via PackedDotProduct.</p>
     *
     * @param samples training vectors
     */
    public void train(float[][] samples) {
        if (samples.length < nlist) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "Need at least nlist (" + nlist + ") samples, got " + samples.length);
        }

        log.info("Training QuantizedIvfPqIndex: {} samples, nlist={}, M={}, type={}",
                samples.length, nlist, numSubspaces, quantizationType);
        long start = System.nanoTime();

        // Step 1: Train IVF centroids via K-Means
        this.centroids = KMeans.train(samples, nlist, 25, 42L);

        // Step 2: Pack centroids for INT4/INT2 coarse quantizer
        if (quantizationType == QuantizationType.SCALAR_INT4
                || quantizationType == QuantizationType.SCALAR_INT2) {
            this.packedCentroids = packCentroids(centroids);
        }

        // Step 3: Compute residuals (vector - nearest centroid)
        float[][] residuals = new float[samples.length][dimensions];
        for (int i = 0; i < samples.length; i++) {
            int cluster = KMeans.nearestCentroid(samples[i], centroids);
            for (int d = 0; d < dimensions; d++) {
                residuals[i][d] = samples[i][d] - centroids[cluster][d];
            }
        }

        // Step 4: Train PQ codebooks on residuals (always used for encoding)
        this.pq = ProductQuantizer.train(residuals, dimensions, numSubspaces);

        this.trained = true;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("QuantizedIvfPqIndex training complete in {}ms", elapsedMs);
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
            // Store full-precision vector for rescore
            int internalIndex = totalVectors;
            floatVectors.add(Arrays.copyOf(vector, vector.length));
            vectorIds.add(id);

            // Assign to nearest cluster
            int cluster = nearestCentroid(vector);

            // Compute residual
            float[] residual = new float[dimensions];
            for (int d = 0; d < dimensions; d++) {
                residual[d] = vector[d] - centroids[cluster][d];
            }

            // Encode residual based on quantization type
            byte[] code = encodeResidual(residual);

            // Add to posting list
            postingLists.get(cluster).add(id, internalIndex, code);
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

    /** Core search logic — safe under optimistic stamp or readLock. */
    private ScoredResult[] doSearch(float[] query, int k) {
        if (totalVectors == 0) return new ScoredResult[0];

        int effectiveK = oversamplingFactor > 1
                ? Math.min(oversamplingFactor * k, totalVectors)
                : k;

        int[] probeClusters = findNearestClusters(query, nprobe);
        List<ScoredResult> candidates = collectCandidates(query, probeClusters, effectiveK);

        if (oversamplingFactor > 1 && !candidates.isEmpty()) {
            return rescoreAndReturn(query, candidates, k);
        }

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

    // ─────────────── Public accessors ───────────────

    /** Returns true if the index has been trained. */
    public boolean isTrained() { return trained; }

    /** Returns the number of clusters. */
    public int nlist() { return nlist; }

    /** Returns the number of probed clusters during search. */
    public int nprobe() { return nprobe; }

    /** Returns the product quantizer (null if not trained). */
    public ProductQuantizer quantizer() { return pq; }

    /** Returns the quantization type used by this index. */
    public QuantizationType quantizationType() { return quantizationType; }

    /** Returns the non-uniform quantizer (INT4/INT2), or null if INT8. */
    public NonUniformQuantizer nonUniformQuantizer() { return nonUniformQuantizer; }

    /** Returns the configured oversampling factor. */
    public int oversamplingFactor() { return oversamplingFactor; }

    // ─────────────── Residual encoding ───────────────

    /**
     * Encodes a residual vector based on the configured quantization type.
     * INT8 uses standard PQ encoding; INT4/INT2 use non-uniform quantization + packing.
     */
    private byte[] encodeResidual(float[] residual) {
        return switch (quantizationType) {
            case SCALAR_INT8 -> pq.encode(residual);
            case SCALAR_INT4 -> {
                int[] levels = nonUniformQuantizer.encode(residual);
                yield NibblePacker.pack(levels, dimensions);
            }
            case SCALAR_INT2 -> {
                int[] levels = nonUniformQuantizer.encode(residual);
                yield CrumbPacker.pack(levels, dimensions);
            }
            default -> pq.encode(residual);
        };
    }

    // ─────────────── Candidate collection ───────────────

    /**
     * Collects and scores candidates from the probed clusters using the appropriate
     * distance computation method for the configured quantization type.
     */
    private List<ScoredResult> collectCandidates(float[] query, int[] probeClusters, int maxCandidates) {
        List<ScoredResult> candidates = new ArrayList<>();

        for (int clusterIdx : probeClusters) {
            PostingList plist = postingLists.get(clusterIdx);
            if (plist.size() == 0) continue;

            // Compute residual query for this cluster
            float[] residualQuery = new float[dimensions];
            for (int d = 0; d < dimensions; d++) {
                residualQuery[d] = query[d] - centroids[clusterIdx][d];
            }

            int size = plist.size();
            byte[][] codes = plist.codes();
            String[] ids = plist.ids();
            int[] indices = plist.storeIndices();

            for (int i = 0; i < size; i++) {
                float dist = computeResidualDistance(residualQuery, codes[i]);
                float score = 1.0f / (1.0f + dist);
                candidates.add(new ScoredResult(ids[i], indices[i], score));
            }
        }

        // Sort by score descending (highest similarity first)
        candidates.sort(java.util.Comparator.naturalOrder());

        // Cap to maxCandidates
        if (candidates.size() > maxCandidates) {
            return new ArrayList<>(candidates.subList(0, maxCandidates));
        }
        return candidates;
    }

    /**
     * Computes distance between a residual query and a stored residual code,
     * dispatching to the appropriate kernel based on quantization type.
     */
    private float computeResidualDistance(float[] residualQuery, byte[] code) {
        return switch (quantizationType) {
            case SCALAR_INT4 -> {
                // PackedDotProduct returns a dot product (higher = more similar)
                // Convert to distance (lower = more similar for L2-like behavior)
                float dotProduct = PackedDotProduct.computeInt4(residualQuery, code, globalCentroids, dimensions);
                yield -dotProduct; // negate so lower = closer
            }
            case SCALAR_INT2 -> {
                float dotProduct = PackedDotProduct.computeInt2(residualQuery, code, globalCentroids, dimensions);
                yield -dotProduct;
            }
            default -> {
                // INT8: Use standard PQ ADC distance
                float[][] distTable = pq.computeDistanceTable(residualQuery);
                yield ProductQuantizer.adcDistance(distTable, code);
            }
        };
    }

    // ─────────────── Rescore ───────────────

    /**
     * Rescores candidates using exact float32 distances and returns the true top-K.
     */
    private ScoredResult[] rescoreAndReturn(float[] query, List<ScoredResult> candidates, int k) {
        List<ScoredResult> rescored = new ArrayList<>(candidates.size());

        for (ScoredResult candidate : candidates) {
            int internalIndex = candidate.index();
            float[] originalVector = floatVectors.get(internalIndex);
            float exactScore = similarityFunction.compute(query, originalVector);
            rescored.add(new ScoredResult(candidate.id(), internalIndex, exactScore));
        }

        // Sort: for similarity metrics (higher is better), descending; for distance, ascending
        if (similarityFunction.higherIsBetter()) {
            rescored.sort(java.util.Comparator.naturalOrder());
        } else {
            rescored.sort(ScoredResult::compareAscending);
        }

        int resultCount = Math.min(k, rescored.size());
        return rescored.subList(0, resultCount).toArray(ScoredResult[]::new);
    }

    // ─────────────── Coarse quantizer (centroid distance) ───────────────

    /**
     * Finds the nearest cluster centroid for a vector.
     * Uses PackedDotProduct for INT4/INT2, squared L2 for INT8.
     */
    private int nearestCentroid(float[] vector) {
        if (packedCentroids != null && globalCentroids != null) {
            return nearestCentroidPacked(vector);
        }
        return nearestCentroidL2(vector);
    }

    /**
     * Nearest centroid using packed dot product distance for INT4/INT2.
     */
    private int nearestCentroidPacked(float[] vector) {
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < nlist; k++) {
            float score;
            if (quantizationType == QuantizationType.SCALAR_INT4) {
                score = PackedDotProduct.computeInt4(vector, packedCentroids[k], globalCentroids, dimensions);
            } else {
                score = PackedDotProduct.computeInt2(vector, packedCentroids[k], globalCentroids, dimensions);
            }
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return best;
    }

    /**
     * Nearest centroid using squared L2 distance (standard path for INT8).
     */
    private int nearestCentroidL2(float[] vector) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int k = 0; k < nlist; k++) {
            float dist = KMeans.squaredL2(vector, centroids[k]);
            if (dist < bestDist) {
                bestDist = dist;
                best = k;
            }
        }
        return best;
    }

    /**
     * Packs cluster centroids using the non-uniform quantizer for fast coarse quantizer
     * distance computation via PackedDotProduct.
     */
    private byte[][] packCentroids(float[][] centroidVectors) {
        byte[][] packed = new byte[centroidVectors.length][];
        for (int i = 0; i < centroidVectors.length; i++) {
            int[] levels = nonUniformQuantizer.encode(centroidVectors[i]);
            if (quantizationType == QuantizationType.SCALAR_INT4) {
                packed[i] = NibblePacker.pack(levels, dimensions);
            } else {
                packed[i] = CrumbPacker.pack(levels, dimensions);
            }
        }
        return packed;
    }

    // ─────────────── Cluster finding ───────────────

    private int[] findNearestClusters(float[] query, int probe) {
        int actualProbe = Math.min(probe, nlist);

        if (packedCentroids != null && globalCentroids != null) {
            return findNearestClustersPacked(query, actualProbe);
        }
        return findNearestClustersL2(query, actualProbe);
    }

    private int[] findNearestClustersPacked(float[] query, int actualProbe) {
        float[] scores = new float[nlist];
        for (int c = 0; c < nlist; c++) {
            if (quantizationType == QuantizationType.SCALAR_INT4) {
                scores[c] = PackedDotProduct.computeInt4(query, packedCentroids[c], globalCentroids, dimensions);
            } else {
                scores[c] = PackedDotProduct.computeInt2(query, packedCentroids[c], globalCentroids, dimensions);
            }
        }

        // Sort by score descending (highest dot product = nearest)
        Integer[] indices = new Integer[nlist];
        for (int i = 0; i < nlist; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Float.compare(scores[b], scores[a]));

        int[] result = new int[actualProbe];
        for (int i = 0; i < actualProbe; i++) {
            result[i] = indices[i];
        }
        return result;
    }

    private int[] findNearestClustersL2(float[] query, int actualProbe) {
        return KMeans.nearestCentroids(query, centroids, actualProbe);
    }


    // ─────────────── Helpers ───────────────

    /**
     * Computes global centroids by averaging per-dimension centroids from the NonUniformQuantizer.
     */
    private static float[] computeGlobalCentroids(NonUniformQuantizer nuq) {
        int levels = nuq.levels();
        int dims = nuq.dimensions();
        float[] global = new float[levels];

        for (int level = 0; level < levels; level++) {
            double sum = 0.0;
            for (int dim = 0; dim < dims; dim++) {
                float[] dimCentroids = nuq.centroids(dim);
                sum += dimCentroids[level];
            }
            global[level] = (float) (sum / dims);
        }
        return global;
    }

    private static float squaredL2(float[] a, float[] b) {
        return KMeans.squaredL2(a, b);
    }
}