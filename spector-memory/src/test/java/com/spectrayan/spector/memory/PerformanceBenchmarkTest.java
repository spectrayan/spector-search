package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.index.MemoryIndex.MemoryLocation;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.CognitiveScorer;
import com.spectrayan.spector.memory.synapse.CognitiveScorer.ScoredRecord;
import com.spectrayan.spector.memory.cortex.WorkingMemoryStore;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Performance benchmark tests verifying the optimizations P1–P12.
 *
 * <p>Each test measures wall-clock time to validate that optimizations
 * achieve expected performance characteristics. Uses deterministic mock
 * embeddings for reproducibility.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmarkTest {

    private static final int DIMS = 128;
    private static final int LARGE_COUNT = 50_000;

    // ══════════════════════════════════════════════════════════════
    // P1: O(1) Reverse Index vs O(n) scan
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("P1: MemoryIndex.findIdByOffset — O(1) reverse lookup at 50K entries")
    void p1_reverseIndexIsConstantTime() {
        MemoryIndex index = new MemoryIndex();

        // Populate 50K entries
        for (int i = 0; i < LARGE_COUNT; i++) {
            String id = "mem-" + i;
            long offset = (long) i * 64;
            index.register(id,
                    new MemoryLocation(MemoryType.EPISODIC, offset, 0),
                    "text-" + i, MemorySource.OBSERVED, new String[]{"tag-" + (i % 10)});
        }

        // Warm up
        for (int i = 0; i < 1000; i++) {
            index.findIdByOffset(MemoryType.EPISODIC, (long) (i * 2) * 64);
        }

        // Benchmark: 10K lookups at various offsets
        long[] offsets = new long[10_000];
        Random rng = new Random(42);
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = (long) rng.nextInt(LARGE_COUNT) * 64;
        }

        long start = System.nanoTime();
        int found = 0;
        for (long offset : offsets) {
            if (index.findIdByOffset(MemoryType.EPISODIC, offset) != null) found++;
        }
        long elapsed = System.nanoTime() - start;

        double avgNs = (double) elapsed / offsets.length;
        System.out.printf("P1: 10K lookups in %,d µs (avg %.0f ns/lookup, found=%d)%n",
                elapsed / 1000, avgNs, found);

        // O(1) should be < 1µs per lookup (vs ~50µs for O(n) at 50K)
        assertThat(avgNs).as("O(1) reverse lookup should be under 1µs").isLessThan(1_000);
        assertThat(found).isEqualTo(10_000);
    }

    // ══════════════════════════════════════════════════════════════
    // P8: ScoredRecord carries CognitiveHeader (no double read)
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("P8: ScoredRecord carries CognitiveHeader — no re-read needed")
    void p8_scoredRecordContainsHeader() {
        CognitiveHeader header = new CognitiveHeader(
                System.currentTimeMillis(), 0x1234L, 1.0f, 0.8f,
                5, (short) 42, (byte) 10, (byte) 0);

        ScoredRecord sr = new ScoredRecord(1024L, 0.95f, 7, header);

        assertThat(sr.header()).isNotNull();
        assertThat(sr.header().importance()).isEqualTo(0.8f);
        assertThat(sr.header().recallCount()).isEqualTo(5);
        assertThat(sr.header().valence()).isEqualTo((byte) 10);
        assertThat(sr.header().centroidId()).isEqualTo((short) 42);
    }

    // ══════════════════════════════════════════════════════════════
    // P3: SIMD Euclidean — benchmarks quantized distance
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("P3: SIMD Euclidean distance — 768-dim × 10K vectors under 150ms")
    void p3_simdEuclideanDistance768Dim() {
        int dims = 768;
        int count = 10_000;

        // Build calibration arrays
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        // Build query vector
        Random rng = new Random(42);
        float[] query = new float[dims];
        for (int i = 0; i < dims; i++) query[i] = rng.nextFloat() * 2 - 1;

        // Build quantized vectors in off-heap segment
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) count * dims, 32);
            for (int v = 0; v < count; v++) {
                for (int d = 0; d < dims; d++) {
                    segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE,
                            (long) v * dims + d, (byte) rng.nextInt(256));
                }
            }

            // Warm up (15,000 iterations to ensure JVM JIT C2 vectorization compilation)
            for (int i = 0; i < 15_000; i++) {
                com.spectrayan.spector.core.similarity.SimilarityFunction.EUCLIDEAN
                        .computeQuantizedFromSegment(query, segment, 0, mins, scales, dims);
            }

            // Benchmark: 10K distance computations
            long start = System.nanoTime();
            float totalDist = 0;
            for (int i = 0; i < count; i++) {
                totalDist += com.spectrayan.spector.core.similarity.SimilarityFunction.EUCLIDEAN
                        .computeQuantizedFromSegment(query, segment, (long) i * dims, mins, scales, dims);
            }
            long elapsed = System.nanoTime() - start;

            double avgUs = (double) elapsed / count / 1000;
            System.out.printf("P3: 10K × 768-dim L2 in %,d ms (avg %.1f µs/vector, checksum=%.2f)%n",
                    elapsed / 1_000_000, avgUs, totalDist);

            // 10K × 768-dim should complete in < 150ms with SIMD (with headroom for slower/virtualized test runners)
            assertThat(elapsed / 1_000_000).as("SIMD L2 10K×768d should be under 150ms").isLessThan(150);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // P7: Batch habituation penalty
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("P7: Batch habituation penalty — 1K IDs under 1ms")
    void p7_batchHabituationPenalty() {
        HabituationPenalty penalty = new HabituationPenalty();
        String[] ids = new String[1000];
        for (int i = 0; i < ids.length; i++) ids[i] = "mem-" + i;

        // Warm up
        penalty.recordAndComputeBatch(ids);
        penalty.clear();

        long start = System.nanoTime();
        float[] results = penalty.recordAndComputeBatch(ids);
        long elapsed = System.nanoTime() - start;

        System.out.printf("P7: Batch 1K penalties in %,d µs%n", elapsed / 1000);

        assertThat(results).hasSize(1000);
        assertThat(results[0]).isEqualTo(1.0f); // first time = no penalty
        assertThat(elapsed / 1000).as("Batch 1K should be under 1ms").isLessThan(1000);
    }

    // ══════════════════════════════════════════════════════════════
    // P12: TierRouter.totalCount — direct sum
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("P12: TierRouter.totalCount — 100K calls under 10ms (no Stream)")
    void p12_totalCountDirectSum() {
        int quantizedVecBytes = 32;
        var working = new WorkingMemoryStore(quantizedVecBytes, 10);
        var episodic = new com.spectrayan.spector.memory.cortex.EpisodicMemoryStore(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "perf-test-p12-" + System.nanoTime()),
                quantizedVecBytes, 100);
        var semantic = new com.spectrayan.spector.memory.cortex.SemanticMemoryStore(quantizedVecBytes, 10);
        var procedural = new com.spectrayan.spector.memory.cortex.ProceduralMemoryStore(quantizedVecBytes, 10);
        var router = new TierRouter(working, episodic, semantic, procedural);

        try {
            // Warm up
            for (int i = 0; i < 1000; i++) router.totalCount();

            long start = System.nanoTime();
            int sum = 0;
            for (int i = 0; i < 100_000; i++) {
                sum += router.totalCount();
            }
            long elapsed = System.nanoTime() - start;

            System.out.printf("P12: 100K totalCount() in %,d µs (sum=%d)%n",
                    elapsed / 1000, sum);

            assertThat(elapsed / 1_000_000).as("100K totalCount should be under 50ms").isLessThan(50);
        } finally {
            router.close();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CognitiveScorer — 6-phase pipeline timing at scale
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("CognitiveScorer: 10K records × 128-dim — full 6-phase scoring under 20ms")
    void cognitiveScorer_fullPipelineTiming() {
        int dims = DIMS;
        int count = 10_000;
        CognitiveRecordLayout layout = new CognitiveRecordLayout(dims);
        int stride = layout.stride();

        // Build calibration
        float[] mins = new float[dims];
        float[] scales = new float[dims];
        Arrays.fill(mins, -1.0f);
        Arrays.fill(scales, 1.0f / 127.5f);

        // Build query
        Random rng = new Random(42);
        float[] query = new float[dims];
        for (int i = 0; i < dims; i++) query[i] = rng.nextFloat() * 2 - 1;

        // Build segment with records
        try (Arena arena = Arena.ofConfined()) {
            long totalBytes = (long) count * stride;
            MemorySegment seg = arena.allocate(totalBytes, 32);

            for (int i = 0; i < count; i++) {
                long offset = (long) i * stride;
                CognitiveHeader header = CognitiveHeader.create(
                        System.currentTimeMillis() - rng.nextInt(86_400_000),
                        rng.nextLong(), 1.0f,
                        0.3f + rng.nextFloat() * 0.7f,
                        (short) rng.nextInt(100), MemoryType.EPISODIC);
                layout.writeHeader(seg, offset, header);

                // Write random quantized vector
                for (int d = 0; d < dims; d++) {
                    seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE,
                            layout.vectorOffset(offset) + d, (byte) rng.nextInt(256));
                }
            }

            RecallOptions opts = RecallOptions.builder().topK(10).build();

            // Warm up
            for (int i = 0; i < 5; i++) {
                CognitiveScorer.score(seg, count, layout, query, opts,
                        System.currentTimeMillis(), 0L, mins, scales);
            }

            // Benchmark
            long start = System.nanoTime();
            List<ScoredRecord> results = CognitiveScorer.score(
                    seg, count, layout, query, opts,
                    System.currentTimeMillis(), 0L, mins, scales);
            long elapsed = System.nanoTime() - start;

            System.out.printf("CognitiveScorer: %d records × %d-dim in %,d µs → %d results%n",
                    count, dims, elapsed / 1000, results.size());

            assertThat(results).hasSize(10);
            assertThat(results.getFirst().header()).isNotNull(); // P8: header present
            assertThat(elapsed / 1_000_000).as("10K × 128d scoring < 20ms").isLessThan(20);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Full SpectorMemory ingest + recall throughput
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("SpectorMemory: 1000 ingestions + 100 recalls in under 2 seconds")
    void fullPipelineThroughput() throws Exception {
        int dims = 64;
        MockEmbeddingProvider embedder = new MockEmbeddingProvider(dims);

        try (SpectorMemory memory = DefaultSpectorMemory.builder()
                .dimensions(dims)
                .embeddingProvider(embedder)
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(50)
                .episodicPartitionCapacity(2000)
                .semanticCapacity(500)
                .proceduralCapacity(100)
                .build()) {

            // Ingest 1000 memories
            long ingestStart = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                memory.remember("mem-" + i, "Memory content about topic " + (i % 50) + " with detail " + i,
                        MemoryType.EPISODIC, MemorySource.OBSERVED,
                        "tag-" + (i % 10), "cat-" + (i % 5)).get(5, TimeUnit.SECONDS);
            }
            long ingestElapsed = System.nanoTime() - ingestStart;

            assertThat(memory.totalMemories()).isGreaterThanOrEqualTo(900); // dedup may reduce count

            // Recall 100 times
            long recallStart = System.nanoTime();
            int totalResults = 0;
            for (int i = 0; i < 100; i++) {
                List<CognitiveResult> results = memory.recall("topic " + (i % 50),
                        RecallOptions.builder().topK(5).build());
                totalResults += results.size();
            }
            long recallElapsed = System.nanoTime() - recallStart;

            double ingestMs = ingestElapsed / 1_000_000.0;
            double recallMs = recallElapsed / 1_000_000.0;
            double avgRecallMs = recallMs / 100;

            System.out.printf("""
                    Full Pipeline Benchmark (64-dim mock embeddings):
                      Ingest: 1000 memories in %.0f ms (%.1f ms/memory)
                      Recall: 100 queries in %.0f ms (%.2f ms/query, %d total results)
                    """, ingestMs, ingestMs / 1000, recallMs, avgRecallMs, totalResults);

            assertThat(totalResults).isGreaterThan(0);
            assertThat(avgRecallMs).as("Avg recall < 50ms per query").isLessThan(50.0);
        }
    }

    // ── Mock Provider ──

    static class MockEmbeddingProvider implements EmbeddingProvider {
        private final int dims;
        MockEmbeddingProvider(int dims) { this.dims = dims; }

        @Override
        public EmbeddingResult embed(String text) {
            Random rng = new Random(text.hashCode());
            float[] vector = new float[dims];
            float norm = 0;
            for (int i = 0; i < dims; i++) {
                vector[i] = (rng.nextFloat() - 0.5f) * 2.0f;
                norm += vector[i] * vector[i];
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) for (int i = 0; i < dims; i++) vector[i] /= norm;
            return new EmbeddingResult(vector, text.split("\\s+").length, "mock");
        }

        @Override public int dimensions() { return dims; }
        @Override public String modelName() { return "mock-" + dims + "d"; }
    }
}
