package com.spectrayan.spector.memory.neurodivergent;

import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.CognitiveResult.RetrievalMode;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.cortex.MemorySource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests for neurodivergent cognitive profiles — HYPERFOCUS, SYSTEMATIZER, DIVERGENT.
 */
class NeurodivergentProfileTest {

    // ── CognitiveProfile.HYPERFOCUS ──

    @Test
    void hyperfocus_pureSimilarityScoring() {
        assertThat(CognitiveProfile.HYPERFOCUS.alpha()).isEqualTo(1.0f);
        assertThat(CognitiveProfile.HYPERFOCUS.beta()).isEqualTo(0.0f);
    }

    @Test
    void hyperfocus_applySetsBoost() {
        RecallOptions opts = RecallOptions.builder()
                .profile(CognitiveProfile.HYPERFOCUS)
                .build();
        assertThat(opts.alpha()).isEqualTo(1.0f);
        assertThat(opts.beta()).isEqualTo(0.0f);
        assertThat(opts.hyperfocusBoost()).isEqualTo(1.5f);
    }

    @Test
    void hyperfocus_noPin() {
        assertThat(CognitiveProfile.HYPERFOCUS.pinSourceEpisodes()).isFalse();
    }

    // ── CognitiveProfile.SYSTEMATIZER ──

    @Test
    void systematizer_importanceDominated() {
        assertThat(CognitiveProfile.SYSTEMATIZER.beta())
                .isGreaterThan(CognitiveProfile.SYSTEMATIZER.alpha());
    }

    @Test
    void systematizer_pinsSourceEpisodes() {
        assertThat(CognitiveProfile.SYSTEMATIZER.pinSourceEpisodes()).isTrue();
    }

    // ── CognitiveProfile.DIVERGENT ──

    @Test
    void divergent_enablesLateralMode() {
        RecallOptions opts = RecallOptions.builder()
                .profile(CognitiveProfile.DIVERGENT)
                .build();
        assertThat(opts.lateralMode()).isTrue();
    }

    @Test
    void divergent_similarityBiased() {
        assertThat(CognitiveProfile.DIVERGENT.alpha())
                .isGreaterThan(CognitiveProfile.DIVERGENT.beta());
    }

    @Test
    void divergent_noPin() {
        assertThat(CognitiveProfile.DIVERGENT.pinSourceEpisodes()).isFalse();
    }

    // ── RecallOptions neurodivergent fields ──

    @Test
    void hyperfocusMask_encodesFromTags() {
        RecallOptions opts = RecallOptions.builder()
                .hyperfocusMask("database", "deadlock")
                .build();
        assertThat(opts.hyperfocusMask()).isNotZero();
    }

    @Test
    void lateralMode_defaults() {
        RecallOptions opts = RecallOptions.DEFAULT;
        assertThat(opts.lateralMode()).isFalse();
        assertThat(opts.lateralDistanceThreshold()).isCloseTo(1.2f, offset(0.01f));
        assertThat(opts.lateralMaxResults()).isGreaterThan(0);
        assertThat(opts.lateralMinTagOverlap()).isCloseTo(0.5f, offset(0.01f));
    }

    @Test
    void lateralMaxResults_autoCalculated() {
        RecallOptions opts = RecallOptions.builder()
                .topK(15)
                .build();
        assertThat(opts.lateralMaxResults()).isEqualTo(5); // 15/3 = 5
    }

    @Test
    void lateralMaxResults_explicitOverride() {
        RecallOptions opts = RecallOptions.builder()
                .topK(15)
                .lateralMaxResults(7)
                .build();
        assertThat(opts.lateralMaxResults()).isEqualTo(7);
    }

    // ── RetrievalMode & CognitiveResult ──

    @Test
    void retrievalMode_standardByDefault() {
        var result = new CognitiveResult(
                "test-id", "text", 0.8f, 1.0f, 0f,
                0, (byte) 0, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[]{"tag"}, 1.0f, 1.0f);
        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.STANDARD);
        assertThat(result.isLateral()).isFalse();
        assertThat(result.isHyperfocused()).isFalse();
    }

    @Test
    void retrievalMode_lateral() {
        var result = new CognitiveResult(
                "test-id", "text", 0.5f, 1.0f, 0f,
                0, (byte) 0, MemoryType.SEMANTIC, MemorySource.OBSERVED,
                new String[]{"cross-domain"}, 1.0f, 1.0f, RetrievalMode.LATERAL);
        assertThat(result.isLateral()).isTrue();
        assertThat(result.isHyperfocused()).isFalse();
    }

    @Test
    void retrievalMode_hyperfocus() {
        var result = new CognitiveResult(
                "test-id", "text", 0.9f, 1.0f, 90f,
                0, (byte) 0, MemoryType.EPISODIC, MemorySource.OBSERVED,
                new String[]{"focus"}, 1.0f, 1.0f, RetrievalMode.HYPERFOCUS);
        assertThat(result.isHyperfocused()).isTrue();
        assertThat(result.isLateral()).isFalse();
    }

    // ── Alpha + Beta normalization for all profiles ──

    @Test
    void allProfiles_alphaAndBeta_sumToOne() {
        for (CognitiveProfile profile : CognitiveProfile.values()) {
            float sum = profile.alpha() + profile.beta();
            assertThat(sum).as("alpha + beta for %s", profile)
                    .isCloseTo(1.0f, offset(0.001f));
        }
    }
}
