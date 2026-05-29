package com.spectrayan.spector.memory.hippocampus;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.ReflectReport;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore.EpisodicPartition;
import com.spectrayan.spector.memory.cortex.SemanticMemoryStore;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.cortex.CentroidRouter;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.embed.TextGenerationProvider;
import com.spectrayan.spector.embed.GenerationOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for IVF centroid clustering in ReflectDaemon (V3.1).
 */
class ReflectDaemonClusteringTest {

    private static final int DIMS = 16;
    private static final int VEC_BYTES = DIMS; // INT8 quantization
    private static final int CAPACITY = 200;

    @TempDir
    Path tempDir;

    private Path storePath;
    private CentroidRouter centroidRouter;
    private MockEmbeddingProvider embeddingProvider;

    @BeforeEach
    void setUp() {
        storePath = tempDir.resolve("episodic");
        centroidRouter = new CentroidRouter(DIMS);
        embeddingProvider = new MockEmbeddingProvider(DIMS);
    }

    // ── V3.1: Centroid-Based Clustering ──

    @Test
    void clustersBycentroidIdAndPromotes() {
        try (EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY);
             SemanticMemoryStore semanticStore = new SemanticMemoryStore(VEC_BYTES, 100)) {

            // Create 20 memories across 3 centroids (ids: 1, 2, 3)
            // Cluster 1: 8 records (above min=5)
            // Cluster 2: 7 records (above min=5)
            // Cluster 3: 3 records (below min=5 — should NOT promote)
            // Unassigned (centroid 0): 2 records
            int[] centroidAssignments = {
                    1, 1, 1, 1, 1, 1, 1, 1,  // 8 records for centroid 1
                    2, 2, 2, 2, 2, 2, 2,     // 7 records for centroid 2
                    3, 3, 3,                   // 3 records for centroid 3
                    0, 0                       // 2 unassigned
            };

            for (int i = 0; i < centroidAssignments.length; i++) {
                CognitiveHeader header = new CognitiveHeader(
                        System.currentTimeMillis(),
                        (long) (i + 1) * 7, // synaptic tags
                        1.0f,                // exactNorm
                        2.0f,                // importance (> 1.0 so V1 fallback would also promote)
                        0,                   // recallCount
                        (short) centroidAssignments[i],  // centroid ID
                        (byte) 0,
                        SynapticHeaderConstants.withMemoryType((byte) 0, MemoryType.EPISODIC.ordinal())
                );
                episodicStore.append(header, makeVec(i));
            }

            assertThat(episodicStore.totalRecords()).isEqualTo(20);
            assertThat(semanticStore.size()).isEqualTo(0);

            // Run reflection with centroid router (V3.1 path)
            ReflectDaemon daemon = new ReflectDaemon(
                    CircadianPolicy.DEFAULT, centroidRouter, null, embeddingProvider);

            ReflectReport report = daemon.runCycle(episodicStore, semanticStore);

            // Should promote 2 clusters (centroid 1 and 2, both ≥ 5 records)
            // Centroid 3 has only 3 records — below threshold
            // Centroid 0 has only 2 records — below threshold
            assertThat(report.consolidatedCount()).isEqualTo(2);
            assertThat(semanticStore.size()).isEqualTo(2);
        }
    }

    @Test
    void withTextGenerationProviderSynthesizes() {
        try (EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY);
             SemanticMemoryStore semanticStore = new SemanticMemoryStore(VEC_BYTES, 100)) {

            // Create 6 memories in the same centroid
            for (int i = 0; i < 6; i++) {
                CognitiveHeader header = new CognitiveHeader(
                        System.currentTimeMillis(),
                        0xFFL, 1.0f, 1.5f,
                        0, // recallCount
                        (short) 5, // centroid 5
                        (byte) 0,
                        SynapticHeaderConstants.withMemoryType((byte) 0, MemoryType.EPISODIC.ordinal())
                );
                episodicStore.append(header, makeVec(i));
            }

            // Mock TextGenerationProvider
            MockTextGenerationProvider mockLlm = new MockTextGenerationProvider();

            // Text lookup function
            Function<Long, String> textLookup = offset -> "Memory text for offset " + offset;

            ReflectDaemon daemon = new ReflectDaemon(
                    CircadianPolicy.DEFAULT, centroidRouter, mockLlm, embeddingProvider);

            ReflectReport report = daemon.runCycle(episodicStore, semanticStore, textLookup);

            // Should promote 1 cluster via LLM synthesis
            assertThat(report.consolidatedCount()).isEqualTo(1);
            assertThat(semanticStore.size()).isEqualTo(1);

            // LLM should have been called
            assertThat(mockLlm.callCount).isGreaterThan(0);
        }
    }

    @Test
    void withoutCentroidRouterUsesV1Fallback() {
        try (EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY);
             SemanticMemoryStore semanticStore = new SemanticMemoryStore(VEC_BYTES, 100)) {

            // Create 5 memories with importance ≥ 1.0
            for (int i = 0; i < 5; i++) {
                CognitiveHeader header = new CognitiveHeader(
                        System.currentTimeMillis(),
                        0L, 1.0f, 2.0f, // importance = 2.0 (above threshold)
                        0, (short) 0, (byte) 0,
                        SynapticHeaderConstants.withMemoryType((byte) 0, MemoryType.EPISODIC.ordinal())
                );
                episodicStore.append(header, makeVec(i));
            }

            // V1 mode — no centroid router
            ReflectDaemon daemon = new ReflectDaemon(CircadianPolicy.DEFAULT);

            ReflectReport report = daemon.runCycle(episodicStore, semanticStore);

            // V1 should promote 1 (the highest-importance record)
            assertThat(report.consolidatedCount()).isEqualTo(1);
            assertThat(semanticStore.size()).isEqualTo(1);
        }
    }

    @Test
    void marksClusterMembersAsConsolidated() {
        try (EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY);
             SemanticMemoryStore semanticStore = new SemanticMemoryStore(VEC_BYTES, 100)) {

            // Create 6 memories in centroid 1
            for (int i = 0; i < 6; i++) {
                CognitiveHeader header = new CognitiveHeader(
                        System.currentTimeMillis(),
                        0L, 1.0f, 1.0f,
                        0, (short) 1, (byte) 0,
                        SynapticHeaderConstants.withMemoryType((byte) 0, MemoryType.EPISODIC.ordinal())
                );
                episodicStore.append(header, makeVec(i));
            }

            ReflectDaemon daemon = new ReflectDaemon(
                    CircadianPolicy.DEFAULT, centroidRouter, null, embeddingProvider);

            daemon.runCycle(episodicStore, semanticStore);

            // All 6 should be marked as consolidated
            EpisodicPartition partition = episodicStore.partitions().getFirst();
            var layout = partition.layout();
            var segment = partition.segment();

            for (int i = 0; i < 6; i++) {
                long offset = partition.recordOffset(i);
                byte flags = layout.readFlags(segment, offset);
                assertThat(SynapticHeaderConstants.isConsolidated(flags))
                        .as("Record %d should be consolidated", i)
                        .isTrue();
            }
        }
    }

    @Test
    void secondReflectDoesNotReprocessConsolidated() {
        try (EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(storePath, VEC_BYTES, CAPACITY);
             SemanticMemoryStore semanticStore = new SemanticMemoryStore(VEC_BYTES, 100)) {

            // 6 memories in centroid 1
            for (int i = 0; i < 6; i++) {
                CognitiveHeader header = new CognitiveHeader(
                        System.currentTimeMillis(),
                        0L, 1.0f, 1.0f,
                        0, (short) 1, (byte) 0,
                        SynapticHeaderConstants.withMemoryType((byte) 0, MemoryType.EPISODIC.ordinal())
                );
                episodicStore.append(header, makeVec(i));
            }

            ReflectDaemon daemon = new ReflectDaemon(
                    CircadianPolicy.DEFAULT, centroidRouter, null, embeddingProvider);

            ReflectReport report1 = daemon.runCycle(episodicStore, semanticStore);
            assertThat(report1.consolidatedCount()).isEqualTo(1);

            // Second reflect — records are already consolidated, nothing new
            ReflectReport report2 = daemon.runCycle(episodicStore, semanticStore);
            assertThat(report2.consolidatedCount()).isEqualTo(0);
        }
    }

    // ── Mock Providers ──

    static class MockEmbeddingProvider implements EmbeddingProvider {
        private final int dims;

        MockEmbeddingProvider(int dims) { this.dims = dims; }

        @Override
        public EmbeddingResult embed(String text) {
            Random rng = new Random(text.hashCode());
            float[] vec = new float[dims];
            float norm = 0f;
            for (int i = 0; i < dims; i++) {
                vec[i] = (rng.nextFloat() - 0.5f) * 2.0f;
                norm += vec[i] * vec[i];
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dims; i++) vec[i] /= norm;
            }
            return new EmbeddingResult(vec, text.split("\\s+").length, "mock-" + dims + "d");
        }

        @Override public int dimensions() { return dims; }
        @Override public String modelName() { return "mock-" + dims + "d"; }
    }

    static class MockTextGenerationProvider implements TextGenerationProvider {
        int callCount = 0;

        @Override
        public String generate(String prompt) {
            callCount++;
            return "Synthesized fact from " + callCount + " call(s).";
        }

        @Override
        public String generate(String prompt, GenerationOptions options) {
            return generate(prompt);
        }

        @Override public String modelName() { return "mock-llm"; }
    }

    // ── Helpers ──

    private byte[] makeVec(int seed) {
        byte[] vec = new byte[VEC_BYTES];
        for (int i = 0; i < VEC_BYTES; i++) {
            vec[i] = (byte) ((seed + i) % 127);
        }
        return vec;
    }
}
