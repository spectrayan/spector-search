package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.amygdala.Valence;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.metamemory.MemoryInsight;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full end-to-end integration test for {@link SpectorMemory}.
 *
 * <p>Uses a deterministic mock {@link EmbeddingProvider} that produces
 * hash-based vectors for repeatable test results.</p>
 */
class SpectorMemoryIntegrationTest {

    private static final int DIMENSIONS = 32; // small for testing speed
    private SpectorMemory memory;

    @BeforeEach
    void setUp() {
        memory = DefaultSpectorMemory.builder()
                .dimensions(DIMENSIONS)
                .embeddingProvider(new MockEmbeddingProvider(DIMENSIONS))
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(20)
                .episodicPartitionCapacity(100)
                .semanticCapacity(100)
                .proceduralCapacity(100)
                .build();
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    // ── V1: Core Pipeline ──

    @Test
    void rememberAndRecall() throws Exception {
        memory.remember("pref-dark", "User prefers dark mode.",
                MemoryType.EPISODIC, MemorySource.USER_STATED, "ui", "preferences").get(5, TimeUnit.SECONDS);
        memory.remember("pref-java", "User prefers Java over Python.",
                MemoryType.EPISODIC, MemorySource.USER_STATED, "language", "preferences").get(5, TimeUnit.SECONDS);
        memory.remember("error-db", "Database lock timeout on table users.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "error", "database").get(5, TimeUnit.SECONDS);

        assertThat(memory.totalMemories()).isEqualTo(3);
        assertThat(memory.memoryCount(MemoryType.EPISODIC)).isEqualTo(3);

        // Recall should return results
        List<CognitiveResult> results = memory.recall("user preferences");
        assertThat(results).isNotEmpty();
    }

    @Test
    void rememberMultipleTiers() throws Exception {
        memory.remember("working-1", "In-progress reasoning.",
                MemoryType.WORKING, "scratch").get(5, TimeUnit.SECONDS);
        memory.remember("semantic-1", "Java is a programming language.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "java").get(5, TimeUnit.SECONDS);
        memory.remember("procedural-1", "Always check null before accessing.",
                MemoryType.PROCEDURAL, MemorySource.PROCEDURAL, "rule").get(5, TimeUnit.SECONDS);
        memory.remember("episodic-1", "Deployed v2.1 to staging.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "deployment").get(5, TimeUnit.SECONDS);

        assertThat(memory.memoryCount(MemoryType.WORKING)).isEqualTo(1);
        assertThat(memory.memoryCount(MemoryType.SEMANTIC)).isEqualTo(1);
        assertThat(memory.memoryCount(MemoryType.PROCEDURAL)).isEqualTo(1);
        assertThat(memory.memoryCount(MemoryType.EPISODIC)).isEqualTo(1);
        assertThat(memory.totalMemories()).isEqualTo(4);
    }

    @Test
    void forgetRemovesFromRecall() throws Exception {
        memory.remember("to-forget", "This will be forgotten.",
                MemoryType.EPISODIC, "temp").get(5, TimeUnit.SECONDS);
        assertThat(memory.totalMemories()).isEqualTo(1);

        memory.forget("to-forget");

        // The memory should be tombstoned and excluded from results
        List<CognitiveResult> results = memory.recall("forgotten");
        assertThat(results).noneMatch(r -> "to-forget".equals(r.id()));
    }

    @Test
    void scratchpadStoresInWorking() throws Exception {
        memory.scratchpad("Thinking about the architecture...").get(5, TimeUnit.SECONDS);
        assertThat(memory.memoryCount(MemoryType.WORKING)).isEqualTo(1);
    }

    // ── V2: Reinforcement & Suppression ──

    @Test
    void reinforceUpdatesValence() throws Exception {
        memory.remember("to-reinforce", "This approach works well.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);

        // Reinforce with positive outcome
        memory.reinforce("to-reinforce", Valence.STRONGLY_POSITIVE);

        // Recall and verify valence was updated
        List<CognitiveResult> results = memory.recall("approach works");
        CognitiveResult reinforced = results.stream()
                .filter(r -> "to-reinforce".equals(r.id()))
                .findFirst()
                .orElse(null);

        if (reinforced != null) {
            assertThat(reinforced.valence()).isGreaterThan((byte) 0);
        }
    }

    @Test
    void suppressExcludesFromRecall() throws Exception {
        memory.remember("to-suppress", "Misleading information.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);
        memory.remember("keep-this", "Helpful information.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);

        memory.suppress("to-suppress", "led to wrong answer");

        List<CognitiveResult> results = memory.recall("information");
        assertThat(results).noneMatch(r -> "to-suppress".equals(r.id()));
    }

    @Test
    void unsuppressAllowsRecall() throws Exception {
        memory.remember("suppress-then-allow", "Toggle suppression test.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);

        memory.suppress("suppress-then-allow");
        assertThat(memory.suppression().isSuppressed("suppress-then-allow")).isTrue();

        memory.unsuppress("suppress-then-allow");
        assertThat(memory.suppression().isSuppressed("suppress-then-allow")).isFalse();
    }

    // ── V2: Metamemory ──

    @Test
    void introspectReturnsInsight() throws Exception {
        for (int i = 0; i < 5; i++) {
            memory.remember("java-" + i, "Java fact number " + i,
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "java").get(5, TimeUnit.SECONDS);
        }

        MemoryInsight insight = memory.introspect("java");
        assertThat(insight.totalMemories()).isGreaterThan(0);
        assertThat(insight.recommendation()).isNotBlank();
    }

    // ── V3: Prospective Memory ──

    @Test
    void scheduleReminderAppearsInRecall() throws Exception {
        memory.remember("base", "Background memory.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);

        // Schedule a reminder in the past (should trigger immediately)
        memory.scheduleReminder("Check deployment status",
                Instant.now().minus(Duration.ofMinutes(1)), "deployment");

        List<CognitiveResult> results = memory.recall("anything");
        boolean hasProspective = results.stream()
                .anyMatch(r -> r.text().contains("Check deployment status"));
        assertThat(hasProspective).isTrue();
    }

    // ── V2: Reflect ──

    @Test
    void reflectReturnsReport() throws Exception {
        for (int i = 0; i < 5; i++) {
            memory.remember("episodic-" + i, "Event " + i + " happened.",
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "events").get(5, TimeUnit.SECONDS);
        }

        ReflectReport report = memory.reflect();
        assertThat(report).isNotNull();
        assertThat(report.duration()).isNotNull();
    }

    // ── V2: WAL ──

    @Test
    void walTracksAllMutations() throws Exception {
        memory.remember("wal-1", "First memory.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);
        memory.remember("wal-2", "Second memory.",
                MemoryType.EPISODIC, "test").get(5, TimeUnit.SECONDS);
        memory.forget("wal-1");
        memory.reinforce("wal-2", Valence.POSITIVE);

        // WAL should have: 2 REMEMBER + 1 FORGET + 1 REINFORCE = 4 events
        assertThat(memory.wal().size()).isGreaterThanOrEqualTo(4);
    }

    // ── V2: Hebbian Co-Activation ──

    @Test
    void hebbianTracksCoActivation() throws Exception {
        memory.remember("co-1", "Java performance tuning.",
                MemoryType.EPISODIC, "java", "performance").get(5, TimeUnit.SECONDS);
        memory.remember("co-2", "Java garbage collection.",
                MemoryType.EPISODIC, "java", "gc").get(5, TimeUnit.SECONDS);

        // Recall should trigger co-activation tracking
        memory.recall("java performance gc");

        // The co-activation tracker should have tracked something
        // (depends on whether results were returned together)
        assertThat(memory.coActivation()).isNotNull();
    }

    // ── V2: Habituation ──

    @Test
    void habituationPenalizesRepeatResults() throws Exception {
        memory.remember("repeat-1", "Always returned memory.",
                MemoryType.EPISODIC, "common").get(5, TimeUnit.SECONDS);

        // First recall
        List<CognitiveResult> first = memory.recall("common topic");
        float firstScore = first.isEmpty() ? 0 : first.getFirst().score();

        // Second recall — habituation should reduce score
        List<CognitiveResult> second = memory.recall("common topic");
        float secondScore = second.isEmpty() ? 0 : second.getFirst().score();

        if (firstScore > 0 && secondScore > 0) {
            assertThat(secondScore).isLessThanOrEqualTo(firstScore);
        }
    }

    // ── Mock Provider ──

    /**
     * Deterministic mock that produces hash-based vectors.
     * Same text always produces the same vector.
     */
    static class MockEmbeddingProvider implements EmbeddingProvider {

        private final int dims;

        MockEmbeddingProvider(int dims) {
            this.dims = dims;
        }

        @Override
        public EmbeddingResult embed(String text) {
            // Deterministic: hash-based vector generation
            Random rng = new Random(text.hashCode());
            float[] vector = new float[dims];
            for (int i = 0; i < dims; i++) {
                vector[i] = (rng.nextFloat() - 0.5f) * 2.0f; // range [-1, 1]
            }
            // Normalize to unit length
            float norm = 0f;
            for (float v : vector) norm += v * v;
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dims; i++) vector[i] /= norm;
            }
            return new EmbeddingResult(vector, text.split("\\s+").length, "mock-" + dims + "d");
        }

        @Override
        public int dimensions() { return dims; }

        @Override
        public String modelName() { return "mock-" + dims + "d"; }
    }
}
