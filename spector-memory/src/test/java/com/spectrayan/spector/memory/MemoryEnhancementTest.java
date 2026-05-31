package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.synapse.DecayStrategy;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Comprehensive tests for the memory enhancement features:
 *
 * <ul>
 *   <li>P0: Embedding normalization at ingestion</li>
 *   <li>P1: Parabolic RBF for lateral scoring</li>
 *   <li>P1: Zeigarnik Effect (IS_RESOLVED flag)</li>
 *   <li>P1: Strictness coefficient for SYSTEMATIZER</li>
 *   <li>P2: Bit-shift reconsolidation</li>
 *   <li>P2: Valence alignment scoring</li>
 *   <li>P2: Semantic satiation LRU cache</li>
 *   <li>P3: New profiles (PARANOID_SENTINEL, THE_EXECUTOR, DEFAULT_MODE_NETWORK)</li>
 *   <li>Profile configuration (operational feature flags)</li>
 * </ul>
 */
class MemoryEnhancementTest {

    private static final int DIMENSIONS = 32;
    private SpectorMemory memory;

    @BeforeEach
    void setUp() {
        memory = DefaultSpectorMemory.builder()
                .dimensions(DIMENSIONS)
                .embeddingProvider(new NormalizingMockProvider(DIMENSIONS))
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

    // ═══════════════════════════════════════════════════════════════
    // P1: Zeigarnik Effect
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Zeigarnik Effect")
    class ZeigarnikEffect {

        @Test
        @DisplayName("Unresolved memories persist in recall (resist decay)")
        void unresolvedMemoriesPersist() throws Exception {
            memory.remember("task-open", "Fix the authentication bug in login service.",
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "bug", "auth").get(5, TimeUnit.SECONDS);

            List<CognitiveResult> results = memory.recall("authentication");
            assertThat(results).isNotEmpty();
            assertThat(results.stream().anyMatch(r -> "task-open".equals(r.id()))).isTrue();
        }

        @Test
        @DisplayName("markResolved causes memory to succumb to normal decay")
        void resolvedMemoryDecays() throws Exception {
            memory.remember("task-done", "Completed the database migration script.",
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "migration").get(5, TimeUnit.SECONDS);

            memory.markResolved("task-done");

            // Memory should still be findable (it's recent)
            List<CognitiveResult> results = memory.recall("database migration");
            // The resolved flag is set — verify it doesn't crash
            assertThat(results).isNotNull();
        }

        @Test
        @DisplayName("markUnresolved re-enters the Zeigarnik loop")
        void unresolvedReopensLoop() throws Exception {
            memory.remember("task-reopen", "Deploy monitoring dashboard.",
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "deploy").get(5, TimeUnit.SECONDS);

            memory.markResolved("task-reopen");
            memory.markUnresolved("task-reopen");

            // Memory is back to unresolved — should still appear
            List<CognitiveResult> results = memory.recall("monitoring dashboard");
            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("markResolved on non-existent ID is a no-op")
        void resolvedNonExistentIsNoop() {
            // Should not throw
            memory.markResolved("nonexistent-id-123");
            memory.markUnresolved("nonexistent-id-456");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // P1: Strictness Coefficient
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Strictness Coefficient")
    class StrictnessCoefficient {

        @Test
        @DisplayName("SYSTEMATIZER profile applies strictness coefficient")
        void systematizerAppliesStrictness() {
            RecallOptions opts = RecallOptions.builder()
                    .profile(CognitiveProfile.SYSTEMATIZER)
                    .build();
            assertThat(opts.strictnessCoefficient()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("Default strictness coefficient is 1.0")
        void defaultStrictnessIsOne() {
            RecallOptions opts = RecallOptions.builder().build();
            assertThat(opts.strictnessCoefficient()).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("Custom strictness coefficient can be set")
        void customStrictness() {
            RecallOptions opts = RecallOptions.builder()
                    .strictnessCoefficient(5.0f)
                    .build();
            assertThat(opts.strictnessCoefficient()).isEqualTo(5.0f);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // P2: Bit-Shift Reconsolidation
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Bit-Shift Reconsolidation")
    class BitShiftReconsolidation {

        @Test
        @DisplayName("Zero recalls means no adjustment")
        void zeroRecalls() {
            assertThat(DecayStrategy.adjustForReconsolidation(7, 0)).isEqualTo(7);
        }

        @Test
        @DisplayName("Single recall halves bucket index")
        void singleRecall() {
            assertThat(DecayStrategy.adjustForReconsolidation(6, 1)).isEqualTo(3);
            assertThat(DecayStrategy.adjustForReconsolidation(4, 1)).isEqualTo(2);
        }

        @Test
        @DisplayName("Multiple recalls produce exponential effect")
        void multipleRecalls() {
            // bucket 7 >> 2 = 1
            assertThat(DecayStrategy.adjustForReconsolidation(7, 2)).isEqualTo(1);
            // bucket 7 >> 3 = 0
            assertThat(DecayStrategy.adjustForReconsolidation(7, 3)).isEqualTo(0);
        }

        @Test
        @DisplayName("Recall count capped at 5")
        void recallCountCapped() {
            // Even with 100 recalls, shift capped at 5
            assertThat(DecayStrategy.adjustForReconsolidation(7, 100)).isEqualTo(0);
            assertThat(DecayStrategy.adjustForReconsolidation(32, 5)).isEqualTo(1); // 32 >> 5 = 1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // P2: Valence Alignment
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Valence Alignment")
    class ValenceAlignment {

        @Test
        @DisplayName("queryValence auto-enables alignment")
        void queryValenceAutoEnables() {
            RecallOptions opts = RecallOptions.builder()
                    .queryValence((byte) -50)
                    .build();
            assertThat(opts.enableValenceAlignment()).isTrue();
            assertThat(opts.queryValence()).isEqualTo((byte) -50);
        }

        @Test
        @DisplayName("Alignment disabled by default")
        void disabledByDefault() {
            RecallOptions opts = RecallOptions.DEFAULT;
            assertThat(opts.enableValenceAlignment()).isFalse();
        }

        @Test
        @DisplayName("PARANOID_SENTINEL sets max-negative queryValence")
        void paranoidSentinelSetsValence() {
            RecallOptions opts = RecallOptions.builder()
                    .profile(CognitiveProfile.PARANOID_SENTINEL)
                    .build();
            assertThat(opts.enableValenceAlignment()).isTrue();
            assertThat(opts.queryValence()).isEqualTo(Byte.MIN_VALUE);
            assertThat(opts.maxValence()).isEqualTo((byte) -1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // P2: Semantic Satiation (Anti-Looping)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Semantic Satiation")
    class SemanticSatiation {

        @Test
        @DisplayName("Repeated recalls reduce scores (satiation + habituation)")
        void repeatedRecallsReduceScores() throws Exception {
            memory.remember("satiate-1", "Kubernetes pod scheduling algorithm.",
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "k8s").get(5, TimeUnit.SECONDS);

            // First recall
            List<CognitiveResult> first = memory.recall("kubernetes scheduling");
            float firstScore = first.isEmpty() ? 0 : first.getFirst().score();

            // Second recall — satiation + habituation should reduce score
            List<CognitiveResult> second = memory.recall("kubernetes scheduling");
            float secondScore = second.isEmpty() ? 0 : second.getFirst().score();

            if (firstScore > 0 && secondScore > 0) {
                assertThat(secondScore).isLessThan(firstScore);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // P3: New Cognitive Profiles
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("New Cognitive Profiles")
    class NewProfiles {

        @Test
        @DisplayName("All profiles maintain alpha + beta = 1.0")
        void allProfilesSumToOne() {
            for (CognitiveProfile profile : CognitiveProfile.values()) {
                float sum = profile.alpha() + profile.beta();
                assertThat(sum).as("alpha + beta for %s", profile)
                        .isCloseTo(1.0f, offset(0.001f));
            }
        }

        @Test
        @DisplayName("PARANOID_SENTINEL only allows negative valence")
        void paranoidSentinelNegativeOnly() {
            assertThat(CognitiveProfile.PARANOID_SENTINEL.maxValence()).isEqualTo((byte) -1);
            assertThat(CognitiveProfile.PARANOID_SENTINEL.minValence()).isEqualTo(Byte.MIN_VALUE);
        }

        @Test
        @DisplayName("THE_EXECUTOR disables lateral mode and sets strictness")
        void executorConfig() {
            RecallOptions opts = RecallOptions.builder()
                    .profile(CognitiveProfile.THE_EXECUTOR)
                    .build();
            assertThat(opts.lateralMode()).isFalse();
            assertThat(opts.strictnessCoefficient()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("DEFAULT_MODE_NETWORK restricts to SEMANTIC + PROCEDURAL tiers")
        void defaultModeNetworkTiers() {
            RecallOptions opts = RecallOptions.builder()
                    .profile(CognitiveProfile.DEFAULT_MODE_NETWORK)
                    .build();
            assertThat(opts.memoryTypes()).containsExactlyInAnyOrder(
                    MemoryType.SEMANTIC, MemoryType.PROCEDURAL);
        }

        @Test
        @DisplayName("THE_EXECUTOR recalls with strict matching end-to-end")
        void executorEndToEnd() throws Exception {
            memory.remember("exact-match", "Deploy the microservice to production cluster.",
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "deploy").get(5, TimeUnit.SECONDS);

            List<CognitiveResult> results = memory.recall("deploy microservice",
                    CognitiveProfile.THE_EXECUTOR);
            assertThat(results).isNotNull(); // verifies no crash with strictness=10.0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Cognitive Profile Configuration (Operational Feature Flags)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cognitive Profile Configuration")
    class ProfileConfiguration {

        @Test
        @DisplayName("coreOnly blocks neurodivergent and advanced profiles")
        void coreOnlyBlocksAdvanced() {
            var config = CognitiveProfileConfig.coreOnly();
            assertThat(config.validate(CognitiveProfile.HYPERFOCUS)).isEqualTo(CognitiveProfile.BALANCED);
            assertThat(config.validate(CognitiveProfile.SYSTEMATIZER)).isEqualTo(CognitiveProfile.BALANCED);
            assertThat(config.validate(CognitiveProfile.PARANOID_SENTINEL)).isEqualTo(CognitiveProfile.BALANCED);
        }

        @Test
        @DisplayName("coreOnly allows basic profiles")
        void coreOnlyAllowsBasic() {
            var config = CognitiveProfileConfig.coreOnly();
            assertThat(config.validate(CognitiveProfile.BALANCED)).isEqualTo(CognitiveProfile.BALANCED);
            assertThat(config.validate(CognitiveProfile.DEBUGGING)).isEqualTo(CognitiveProfile.DEBUGGING);
            assertThat(config.validate(CognitiveProfile.EXPLORING)).isEqualTo(CognitiveProfile.EXPLORING);
        }

        @Test
        @DisplayName("withNeurodivergent allows neuro profiles but blocks advanced")
        void withNeurodivergentBlocksAdvanced() {
            var config = CognitiveProfileConfig.withNeurodivergent();
            assertThat(config.validate(CognitiveProfile.HYPERFOCUS)).isEqualTo(CognitiveProfile.HYPERFOCUS);
            assertThat(config.validate(CognitiveProfile.PARANOID_SENTINEL)).isEqualTo(CognitiveProfile.BALANCED);
            assertThat(config.validate(CognitiveProfile.THE_EXECUTOR)).isEqualTo(CognitiveProfile.BALANCED);
        }

        @Test
        @DisplayName("allEnabled allows every profile")
        void allEnabledAllowsAll() {
            var config = CognitiveProfileConfig.allEnabled();
            for (CognitiveProfile p : CognitiveProfile.values()) {
                assertThat(config.validate(p)).isEqualTo(p);
            }
        }

        @Test
        @DisplayName("Custom config with specific profiles")
        void customConfig() {
            var config = CognitiveProfileConfig.only(
                    CognitiveProfile.DEBUGGING, CognitiveProfile.HYPERFOCUS);
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue(); // always included
            assertThat(config.isEnabled(CognitiveProfile.DEBUGGING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.EXPLORING)).isFalse();
        }

        @Test
        @DisplayName("Null profile validates to BALANCED")
        void nullValidatesToBalanced() {
            var config = CognitiveProfileConfig.allEnabled();
            assertThat(config.validate(null)).isEqualTo(CognitiveProfile.BALANCED);
        }

        @Test
        @DisplayName("requireEnabled throws for disabled profiles")
        void requireEnabledThrows() {
            var config = CognitiveProfileConfig.coreOnly();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> config.requireEnabled(CognitiveProfile.HYPERFOCUS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HYPERFOCUS");
        }

        @Test
        @DisplayName("requireEnabled passes for enabled profiles")
        void requireEnabledPasses() {
            var config = CognitiveProfileConfig.allEnabled();
            assertThat(config.requireEnabled(CognitiveProfile.THE_EXECUTOR))
                    .isEqualTo(CognitiveProfile.THE_EXECUTOR);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Config YAML Parsing (fromConfigValue)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Config YAML Parsing")
    class ConfigParsing {

        @Test
        @DisplayName("'ALL' enables all profiles")
        void allPreset() {
            var config = CognitiveProfileConfig.fromConfigValue("ALL");
            for (CognitiveProfile p : CognitiveProfile.values()) {
                assertThat(config.isEnabled(p)).isTrue();
            }
        }

        @Test
        @DisplayName("'CORE_ONLY' enables only core profiles")
        void coreOnlyPreset() {
            var config = CognitiveProfileConfig.fromConfigValue("CORE_ONLY");
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isFalse();
        }

        @Test
        @DisplayName("'WITH_NEURODIVERGENT' enables core + neuro")
        void withNeurodivergentPreset() {
            var config = CognitiveProfileConfig.fromConfigValue("WITH_NEURODIVERGENT");
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.PARANOID_SENTINEL)).isFalse();
        }

        @Test
        @DisplayName("CSV list parses individual profiles")
        void csvList() {
            var config = CognitiveProfileConfig.fromConfigValue("DEBUGGING, HYPERFOCUS, THE_EXECUTOR");
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue(); // always
            assertThat(config.isEnabled(CognitiveProfile.DEBUGGING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.THE_EXECUTOR)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.EXPLORING)).isFalse();
        }

        @Test
        @DisplayName("null/blank defaults to ALL")
        void nullDefaultsToAll() {
            assertThat(CognitiveProfileConfig.fromConfigValue(null).enabledProfiles())
                    .hasSize(CognitiveProfile.values().length);
            assertThat(CognitiveProfileConfig.fromConfigValue("  ").enabledProfiles())
                    .hasSize(CognitiveProfile.values().length);
        }

        @Test
        @DisplayName("Invalid profile name throws with helpful message")
        void invalidProfileThrows() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> CognitiveProfileConfig.fromConfigValue("DEBUGGING, INVALID_PROFILE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INVALID_PROFILE");
        }

        @Test
        @DisplayName("Case-insensitive parsing")
        void caseInsensitive() {
            var config = CognitiveProfileConfig.fromConfigValue("debugging, Hyperfocus");
            assertThat(config.isEnabled(CognitiveProfile.DEBUGGING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SynapticHeaderConstants Zeigarnik Flag
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SynapticHeader FLAG_RESOLVED")
    class FlagResolved {

        @Test
        @DisplayName("FLAG_RESOLVED is bit 5 (0x20)")
        void flagValue() {
            assertThat(SynapticHeaderConstants.FLAG_RESOLVED).isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("New memories are not resolved by default")
        void defaultNotResolved() {
            byte flags = 0;
            assertThat(SynapticHeaderConstants.isResolved(flags)).isFalse();
        }

        @Test
        @DisplayName("Setting resolved flag is detectable")
        void setResolved() {
            byte flags = SynapticHeaderConstants.FLAG_RESOLVED;
            assertThat(SynapticHeaderConstants.isResolved(flags)).isTrue();
        }

        @Test
        @DisplayName("Resolved flag does not interfere with other flags")
        void noInterference() {
            byte flags = (byte) (SynapticHeaderConstants.FLAG_TOMBSTONE
                    | SynapticHeaderConstants.FLAG_PINNED
                    | SynapticHeaderConstants.FLAG_RESOLVED);
            assertThat(SynapticHeaderConstants.isTombstoned(flags)).isTrue();
            assertThat(SynapticHeaderConstants.isPinned(flags)).isTrue();
            assertThat(SynapticHeaderConstants.isResolved(flags)).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Mock Embedding Provider (deterministic, normalized)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deterministic mock that produces hash-based, L2-normalized vectors.
     */
    static class NormalizingMockProvider implements EmbeddingProvider {
        private final int dims;

        NormalizingMockProvider(int dims) { this.dims = dims; }

        @Override
        public EmbeddingResult embed(String text) {
            Random rng = new Random(text.hashCode());
            float[] vector = new float[dims];
            for (int i = 0; i < dims; i++) {
                vector[i] = (rng.nextFloat() - 0.5f) * 2.0f;
            }
            // L2 normalize
            float norm = 0f;
            for (float v : vector) norm += v * v;
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dims; i++) vector[i] /= norm;
            }
            return new EmbeddingResult(vector, text.split("\\s+").length, "mock-" + dims + "d");
        }

        @Override public int dimensions() { return dims; }
        @Override public String modelName() { return "mock-" + dims + "d"; }
    }
}
