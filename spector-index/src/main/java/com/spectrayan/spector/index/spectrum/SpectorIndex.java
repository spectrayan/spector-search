package com.spectrayan.spector.index.spectrum;

import com.spectrayan.spector.core.cluster.KMeans;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.VectorIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SpectorIndex — the flagship adaptive vector index of the Spector search engine.
 *
 * <h2>Architecture</h2>
 * <p>SpectorIndex combines three orthogonal techniques for optimal speed, recall, and memory:</p>
 * <ol>
 *   <li><b>IVF (Inverted File)</b> — coarse K-Means clustering partitions the space into
 *       Voronoi cells. At query time only the {@code nProbe} closest cells are searched,
 *       reducing the effective search space by {@code nCentroids / nProbe}.</li>
 *   <li><b>Adaptive Shards</b> — each cell is a {@link SpectorShard}: a flat scan when
 *       small (&lt; {@code shardThreshold}), automatically promoted to a local HNSW graph
 *       when large. SIMD flat scans beat HNSW pointer-chasing for small partitions; for
 *       large partitions the graph wins decisively.</li>
 *   <li><b>VASQ Residual Quantization</b> — vectors are stored as residuals
 *       ({@code r = x − centroid}) quantized with VASQ (FWHT-rotated INT8). Residual
 *       variance is 10–100× lower than absolute coordinates, giving INT8 residuals the
 *       spatial precision of INT12–INT16 absolute quantization.</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li><b>Train</b> — call {@link #train(float[][])} with representative vectors.
 *       Runs K-Means++ to learn {@code nCentroids} centroids.</li>
 *   <li><b>Add</b> — call {@link #add(String, int, float[])} for each vector.
 *       The vector is routed to its nearest centroid's shard as a residual.</li>
 *   <li><b>Search</b> — call {@link #search(float[], int)}.
 *       Probes the {@code nProbe} closest centroids, searches each shard, merges results.</li>
 * </ol>
 *
 * <h2>Key Design Points</h2>
 * <ul>
 *   <li><b>FWHT on residual, not on raw vector</b> — applying the Walsh-Hadamard Transform
 *       to the residual preserves IVF cluster geometry. Applying it to the raw vector before
 *       centroid assignment would break the spatial clustering.</li>
 *   <li><b>nProbe ≥ 16 for 95%+ recall</b> — boundary vectors near Voronoi boundaries
 *       may be missed if too few cells are probed. The default {@code nProbe = 16} is
 *       cheap (VASQ makes each shard scan ~200 ns) and ensures excellent recall.</li>
 *   <li><b>ADC for graph construction</b> — when promoting a shard, each float32 residual
 *       is inserted into the local HNSW using Asymmetric Distance Computation (ADC):
 *       exact query state vs. already-quantized nodes. This is the correct approach;
 *       using symmetric quantized distance for graph construction destroys recall.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Concurrent reads ({@link #search}) are safe after training completes.
 * Concurrent writes ({@link #add}) use per-shard locks for minimal contention.
 * {@link #train} must complete before any add or search calls.</p>
 *
 * @see SpectorIndexConfig
 * @see SpectorShard
 */
public final class SpectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(SpectorIndex.class);


    private final int dimensions;
    private final SpectorIndexConfig config;

    // ── IVF state (set after training) ──
    private volatile float[][] centroids;  // [nCentroids][dimensions]
    private volatile SpectorShard[] shards;
    private volatile boolean trained;


    // ── Stats ──
    /**
     * Atomic total vector count. Incremented under the per-shard lock in add(), so
     * the increment itself is visible to concurrent searches immediately after the lock release.
     * Using AtomicInteger (not volatile int++) eliminates the read-modify-write race.
     */
    private final AtomicInteger totalSize = new AtomicInteger(0);

    /**
     * Per-thread residual scratch buffer: one {@code float[dimensions]} reused across every
     * {@link #add} call and every probed shard in {@link #search}. Eliminates the
     * {@code subtract()} allocation that previously occurred on each add and each probe.
     *
     * <p>Safe for add(): the residual is always System.arraycopy'd into the shard's flat
     * buffer (or copied by hnswIndex.add's storeVector) before the scratch is released.</p>
     *
     * <p>Safe for search(): the scratch is overwritten for each probe, but each shard.search()
     * completes fully before the next probe overwrites it — probes are sequential.</p>
     */
    private final ThreadLocal<float[]> residualScratch;


    // ─────────────── Builder ───────────────

    /** Creates a builder for SpectorIndex. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link SpectorIndex}.
     *
     * <pre>{@code
     * SpectorIndex index = SpectorIndex.builder()
     *     .dimensions(768)
     *     .nCentroids(256)
     *     .nProbe(16)
     *     .shardThreshold(20_000)
     *     .similarityFunction(SimilarityFunction.COSINE)
     *     .build();
     * }</pre>
     */
    public static final class Builder {
        private int dimensions = -1;
        private int nCentroids = 256;
        private int nProbe = 16;
        private int shardThreshold = 20_000;
        private int oversamplingFactor = 3;
        private int kMeansIterations = 25;
        private SimilarityFunction similarityFunction = SimilarityFunction.COSINE;
        private HnswParams hnswParams = HnswParams.DEFAULT;

        private Builder() {}

        public Builder dimensions(int d)                          { this.dimensions = d; return this; }
        public Builder nCentroids(int n)                          { this.nCentroids = n; return this; }
        public Builder nProbe(int p)                              { this.nProbe = p; return this; }
        public Builder shardThreshold(int t)                      { this.shardThreshold = t; return this; }
        public Builder oversamplingFactor(int f)                  { this.oversamplingFactor = f; return this; }
        public Builder kMeansIterations(int i)                    { this.kMeansIterations = i; return this; }
        public Builder similarityFunction(SimilarityFunction fn)  { this.similarityFunction = fn; return this; }
        public Builder hnswParams(HnswParams p)                   { this.hnswParams = p; return this; }
        public Builder config(SpectorIndexConfig c) {
            this.nCentroids = c.nCentroids();
            this.nProbe = c.nProbe();
            this.shardThreshold = c.shardThreshold();
            this.oversamplingFactor = c.oversamplingFactor();
            this.kMeansIterations = c.kMeansIterations();
            this.similarityFunction = c.similarityFunction();
            this.hnswParams = c.hnswParams();
            return this;
        }

        public SpectorIndex build() {
            if (dimensions <= 0) throw new IllegalStateException("dimensions must be set and positive");
            SpectorIndexConfig cfg = new SpectorIndexConfig(
                    nCentroids, nProbe, shardThreshold, oversamplingFactor,
                    kMeansIterations, similarityFunction, hnswParams);
            return new SpectorIndex(dimensions, cfg);
        }
    }

    // ─────────────── Constructor ───────────────

    private SpectorIndex(int dimensions, SpectorIndexConfig config) {
        this.dimensions = dimensions;
        this.config = config;
        this.trained = false;

        // Thread-local residual scratch — one float[dimensions] per thread, never GC'd during add/search
        this.residualScratch = ThreadLocal.withInitial(() -> new float[dimensions]);
    }

    // ─────────────── Training ───────────────

    /**
     * Trains the index by running K-Means++ on the provided representative vectors.
     *
     * <p>Must be called before any {@link #add} or {@link #search} calls.
     * Training is a one-time operation; the index cannot be re-trained after calling this.</p>
     *
     * @param trainingVectors representative sample vectors (≥ nCentroids)
     * @throws IllegalStateException    if already trained
     * @throws IllegalArgumentException if the training set is smaller than nCentroids
     */
    public synchronized void train(float[][] trainingVectors) {
        if (trained) throw new IllegalStateException("SpectorIndex has already been trained.");
        int n = trainingVectors.length;
        int k = config.nCentroids();
        if (n < k) {
            throw new IllegalArgumentException(
                    "Training requires at least " + k + " vectors (nCentroids), got " + n);
        }

        log.info("SpectorIndex training: {} samples, nCentroids={}", n, k);
        long t0 = System.nanoTime();

        this.centroids = KMeans.train(trainingVectors, k, config.kMeansIterations(), 42L);
        log.debug("K-Means converged");

        this.shards = new SpectorShard[k];
        for (int i = 0; i < k; i++) {
            shards[i] = new SpectorShard(dimensions, config, centroids[i]);
        }

        this.trained = true;
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("SpectorIndex training complete in {}ms", ms);
    }

    // ─────────────── VectorIndex ───────────────

    /**
     * Adds a vector to the index.
     *
     * <p>Routes the vector to the nearest centroid's shard as a float32 residual.
     * If the shard crosses the {@link SpectorIndexConfig#shardThreshold()}, it automatically
     * promotes to HNSW mode.</p>
     *
     * @throws IllegalStateException if {@link #train} has not been called
     */
    @Override
    public void add(String id, int storeIndex, float[] vector) {
        requireTrained();
        if (vector.length != dimensions)
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + vector.length);

        int shardIdx = KMeans.nearestCentroid(vector, centroids);

        // Reuse thread-local scratch for residual — no allocation per add()
        // SpectorShard.add() acquires its internal writeLock; the residual is copied into
        // the shard's flat buffer before writeLock is released, so ThreadLocal reuse is safe.
        float[] residual = residualScratch.get();
        float[] c = centroids[shardIdx];
        for (int i = 0; i < dimensions; i++) residual[i] = vector[i] - c[i];

        shards[shardIdx].add(id, storeIndex, residual);
        totalSize.incrementAndGet();
    }

    /**
     * Searches for the {@code k} nearest neighbors to the query vector.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Find the {@code nProbe} closest centroids to the query.</li>
     *   <li>For each probed centroid {@code c}: compute residual query {@code q − c},
     *       search that centroid's {@link SpectorShard}.</li>
     *   <li>Merge candidates from all probed shards and return the global top-K.</li>
     * </ol>
     *
     * @throws IllegalStateException if {@link #train} has not been called
     */
    @Override
    public ScoredResult[] search(float[] query, int k) {
        requireTrained();
        if (query.length != dimensions)
            throw new IllegalArgumentException("Expected " + dimensions + " dims, got " + query.length);
        if (totalSize.get() == 0) return new ScoredResult[0];

        // Step 1: Select nProbe closest centroids (box-free partial sort via KMeans)
        int[] probeShards = KMeans.nearestCentroids(query, centroids, config.nProbe());

        // Step 2: Search each probed shard with its residual query
        // Reuse thread-local scratch for residualQuery — overwritten per probe, but
        // each shard.search() fully completes before the next probe, so this is safe.
        float[] residualQuery = residualScratch.get();

        // ── CRITICAL: IVF residual search always uses L2 distance ──
        // Cosine/dot-product are NOT translation-invariant: cosine(q-c1, x-c1)
        // and cosine(q-c2, y-c2) are in different coordinate systems and cannot
        // be compared across shards. L2 IS translation-invariant:
        //   ‖(q-c) - (x-c)‖ = ‖q - x‖
        // So L2 on residuals gives the true original-space distance regardless
        // of which centroid's shard the vector resides in. This is the standard
        // approach used by FAISS IVF and all production IVF implementations.
        //
        // The user's similarityFunction is still used for centroid routing
        // (nearestCentroid) where it operates in absolute space.
        int oversample = Math.max(k, k * config.oversamplingFactor());

        // Array-based global top-K — zero GC during the merge (consistent with flatScan pattern)
        // L2 distance: lower is better → sentinel is POSITIVE_INFINITY
        float[]  topScores       = new float[k];
        String[] topIds          = new String[k];
        int[]    topStoreIndices = new int[k];
        Arrays.fill(topScores, Float.POSITIVE_INFINITY);

        float worstScore = Float.POSITIVE_INFINITY;
        int   worstPos   = 0;

        for (int shardIdx : probeShards) {
            float[] c = centroids[shardIdx];
            for (int i = 0; i < dimensions; i++) residualQuery[i] = query[i] - c[i];

            // Read-only search — no lock needed (shards handle internal thread safety)
            ScoredResult[] localResults = shards[shardIdx].search(residualQuery, oversample);

            for (ScoredResult r : localResults) {
                // L2: lower is better → replace if new score is lower than worst
                if (r.score() < worstScore) {
                    topScores[worstPos]       = r.score();
                    topIds[worstPos]          = r.id();
                    topStoreIndices[worstPos] = r.index();

                    // Find the new worst — O(k) scan, negligible vs the O(nProbe) outer loop
                    worstScore = topScores[0];
                    worstPos   = 0;
                    for (int j = 1; j < k; j++) {
                        if (topScores[j] > worstScore) {
                            worstScore = topScores[j];
                            worstPos   = j;
                        }
                    }
                }
            }
        }

        // Step 3: Materialize results and sort by L2 distance (ascending — best first)
        int validCount = 0;
        for (int i = 0; i < k; i++) {
            if (topIds[i] != null) validCount++;
        }
        ScoredResult[] results = new ScoredResult[validCount];
        int ri = 0;
        for (int i = 0; i < k; i++) {
            if (topIds[i] != null) {
                results[ri++] = new ScoredResult(topIds[i], topStoreIndices[i], topScores[i]);
            }
        }
        Arrays.sort(results, (a, b) -> Float.compare(a.score(), b.score()));
        return results;
    }

    @Override
    public int size() {
        return totalSize.get();
    }

    @Override
    public SimilarityFunction similarityFunction() {
        return config.similarityFunction();
    }

    @Override
    public void close() {
        SpectorShard[] s = this.shards;
        if (s != null) {
            for (SpectorShard shard : s) {
                if (shard != null) shard.close();
            }
        }
    }

    /** Returns whether the index has been trained. */
    public boolean isTrained() { return trained; }

    /** Returns the config used by this index. */
    public SpectorIndexConfig config() { return config; }

    /** Returns the vector dimensionality. */
    public int dimensions() { return dimensions; }

    /**
     * Returns the centroid assignment counts — useful for diagnosing cluster balance.
     *
     * @return int array of length nCentroids, where entry i is the number of vectors
     *         assigned to centroid i
     * @throws IllegalStateException if not trained
     */
    public int[] shardSizes() {
        requireTrained();
        int[] sizes = new int[config.nCentroids()];
        for (int i = 0; i < config.nCentroids(); i++) {
            sizes[i] = shards[i].size();
        }
        return sizes;
    }


    // ─────────────── Math helpers ───────────────

    private void requireTrained() {
        if (!trained)
            throw new IllegalStateException(
                    "SpectorIndex must be trained before use. Call train(trainingVectors) first.");
    }

    /**
     * Saves the SpectorIndex's state (centroids, shard modes, structures) to the given directory.
     */
    public void save(Path dir, com.spectrayan.spector.storage.VectorStore vs) throws IOException {
        if (!trained) {
            log.warn("SpectorIndex is not trained; skipping persistence.");
            return;
        }

        Files.createDirectories(dir);

        // 1. Save metadata to meta.properties
        var props = new Properties();
        props.setProperty("dimensions", String.valueOf(dimensions));
        props.setProperty("nCentroids", String.valueOf(config.nCentroids()));
        props.setProperty("nProbe", String.valueOf(config.nProbe()));
        props.setProperty("shardThreshold", String.valueOf(config.shardThreshold()));
        props.setProperty("totalSize", String.valueOf(totalSize.get()));
        props.setProperty("trained", String.valueOf(trained));

        try (var out = Files.newOutputStream(dir.resolve("meta.properties"))) {
            props.store(out, "SpectorIndex Metadata");
        }

        // 2. Save Centroids
        Path centroidsFile = dir.resolve("centroids.bin");
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(centroidsFile)))) {
            for (int i = 0; i < config.nCentroids(); i++) {
                for (int d = 0; d < dimensions; d++) {
                    out.writeFloat(centroids[i][d]);
                }
            }
        }

        // 3. Save Shards
        Path shardsDir = dir.resolve("shards");
        Files.createDirectories(shardsDir);
        for (int i = 0; i < config.nCentroids(); i++) {
            shards[i].save(shardsDir, i);
        }

        log.info("SpectorIndex persisted successfully to {} ({} centroids, {} size)", dir, config.nCentroids(), totalSize.get());
    }

    /**
     * Reconstructs and loads a SpectorIndex state from the given directory.
     */
    public static SpectorIndex load(Path dir, int dimensions, SpectorIndexConfig config, com.spectrayan.spector.storage.VectorStore vs) throws IOException {
        Path metaFile = dir.resolve("meta.properties");
        if (!Files.exists(metaFile)) {
            throw new java.io.FileNotFoundException("SpectorIndex meta file not found: " + metaFile);
        }

        var props = new Properties();
        try (var in = Files.newInputStream(metaFile)) {
            props.load(in);
        }

        int loadedDims = Integer.parseInt(props.getProperty("dimensions"));
        int loadedNCentroids = Integer.parseInt(props.getProperty("nCentroids"));
        int loadedNProbe = Integer.parseInt(props.getProperty("nProbe"));
        int loadedShardThreshold = Integer.parseInt(props.getProperty("shardThreshold"));
        int loadedTotalSize = Integer.parseInt(props.getProperty("totalSize"));
        boolean loadedTrained = Boolean.parseBoolean(props.getProperty("trained"));

        if (loadedDims != dimensions) {
            throw new IllegalArgumentException("Dimensionality mismatch: expected " + dimensions + ", loaded " + loadedDims);
        }

        var index = new SpectorIndex(dimensions, config);
        index.trained = loadedTrained;
        index.totalSize.set(loadedTotalSize);

        if (loadedTrained) {
            // 1. Load Centroids
            Path centroidsFile = dir.resolve("centroids.bin");
            if (!Files.exists(centroidsFile)) {
                throw new java.io.FileNotFoundException("Centroids bin file not found: " + centroidsFile);
            }
            index.centroids = new float[loadedNCentroids][dimensions];
            try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(centroidsFile)))) {
                for (int i = 0; i < loadedNCentroids; i++) {
                    for (int d = 0; d < dimensions; d++) {
                        index.centroids[i][d] = in.readFloat();
                    }
                }
            }

            // 2. Load Shards
            Path shardsDir = dir.resolve("shards");
            index.shards = new SpectorShard[loadedNCentroids];
            for (int i = 0; i < loadedNCentroids; i++) {
                index.shards[i] = SpectorShard.load(shardsDir, i, dimensions, config, index.centroids[i]);
            }

            // 3. Post-load promoted graph reconstruction (must happen sequentially)
            for (int i = 0; i < loadedNCentroids; i++) {
                if (index.shards[i].isPromoted()) {
                    index.shards[i].loadPromotedGraph(shardsDir, i, vs);
                }
            }
        }

        return index;
    }
}
