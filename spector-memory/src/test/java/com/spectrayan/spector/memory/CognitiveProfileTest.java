package com.spectrayan.spector.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CognitiveProfile} — thalamic modulation presets.
 */
class CognitiveProfileTest {

    @Test
    void balancedProfile_defaultWeights() {
        assertThat(CognitiveProfile.BALANCED.alpha()).isEqualTo(0.6f);
        assertThat(CognitiveProfile.BALANCED.beta()).isEqualTo(0.4f);
        assertThat(CognitiveProfile.BALANCED.minValence()).isEqualTo(Byte.MIN_VALUE);
        assertThat(CognitiveProfile.BALANCED.maxValence()).isEqualTo(Byte.MAX_VALUE);
    }

    @Test
    void debuggingProfile_negativeValenceBias() {
        assertThat(CognitiveProfile.DEBUGGING.alpha()).isLessThan(CognitiveProfile.DEBUGGING.beta());
        assertThat(CognitiveProfile.DEBUGGING.maxValence()).isLessThan((byte) 0);
    }

    @Test
    void exploringProfile_similarityDominated() {
        assertThat(CognitiveProfile.EXPLORING.alpha()).isGreaterThan(CognitiveProfile.EXPLORING.beta());
        assertThat(CognitiveProfile.EXPLORING.minValence()).isEqualTo(Byte.MIN_VALUE);
        assertThat(CognitiveProfile.EXPLORING.maxValence()).isEqualTo(Byte.MAX_VALUE);
    }

    @Test
    void recallingProfile_positiveValenceBias() {
        assertThat(CognitiveProfile.RECALLING.minValence()).isGreaterThan((byte) 0);
        assertThat(CognitiveProfile.RECALLING.maxValence()).isEqualTo(Byte.MAX_VALUE);
    }

    @Test
    void criticalProfile_importanceDominated() {
        assertThat(CognitiveProfile.CRITICAL.beta()).isGreaterThan(CognitiveProfile.CRITICAL.alpha());
        assertThat(CognitiveProfile.CRITICAL.beta()).isEqualTo(0.8f);
    }

    @Test
    void applyTo_setsBuilderFields() {
        RecallOptions options = RecallOptions.builder()
                .profile(CognitiveProfile.DEBUGGING)
                .topK(20)
                .build();

        assertThat(options.alpha()).isEqualTo(CognitiveProfile.DEBUGGING.alpha());
        assertThat(options.beta()).isEqualTo(CognitiveProfile.DEBUGGING.beta());
        assertThat(options.minValence()).isEqualTo(CognitiveProfile.DEBUGGING.minValence());
        assertThat(options.maxValence()).isEqualTo(CognitiveProfile.DEBUGGING.maxValence());
        assertThat(options.topK()).isEqualTo(20); // independent of profile
    }

    @Test
    void profileOverrides_workCorrectly() {
        // Profile sets alpha=0.3, but explicit override changes it to 0.5
        RecallOptions options = RecallOptions.builder()
                .profile(CognitiveProfile.DEBUGGING)
                .alpha(0.5f) // override profile's alpha
                .build();

        assertThat(options.alpha()).isEqualTo(0.5f); // overridden
        assertThat(options.beta()).isEqualTo(CognitiveProfile.DEBUGGING.beta()); // from profile
    }

    @Test
    void detectFromTags_debuggingKeywords() {
        assertThat(CognitiveProfile.detect("error", "database")).isEqualTo(CognitiveProfile.DEBUGGING);
        assertThat(CognitiveProfile.detect("crash-report")).isEqualTo(CognitiveProfile.DEBUGGING);
        assertThat(CognitiveProfile.detect("fix", "urgent")).isEqualTo(CognitiveProfile.CRITICAL); // critical > debug
    }

    @Test
    void detectFromTags_recallingKeywords() {
        assertThat(CognitiveProfile.detect("solution", "patterns")).isEqualTo(CognitiveProfile.RECALLING);
        assertThat(CognitiveProfile.detect("best-practice")).isEqualTo(CognitiveProfile.RECALLING);
    }

    @Test
    void detectFromTags_criticalKeywords() {
        assertThat(CognitiveProfile.detect("security", "api")).isEqualTo(CognitiveProfile.CRITICAL);
        assertThat(CognitiveProfile.detect("production", "outage")).isEqualTo(CognitiveProfile.CRITICAL);
    }

    @Test
    void detectFromTags_noMatch_returnsBalanced() {
        assertThat(CognitiveProfile.detect("java", "spring")).isEqualTo(CognitiveProfile.BALANCED);
        assertThat(CognitiveProfile.detect()).isEqualTo(CognitiveProfile.BALANCED);
        assertThat(CognitiveProfile.detect((String[]) null)).isEqualTo(CognitiveProfile.BALANCED);
    }

    @Test
    void detectPriority_criticalOverDebugging() {
        // "critical" + "error" → CRITICAL wins (higher priority)
        assertThat(CognitiveProfile.detect("critical", "error"))
                .isEqualTo(CognitiveProfile.CRITICAL);
    }

    @Test
    void allProfilesSumToOne() {
        for (CognitiveProfile profile : CognitiveProfile.values()) {
            float sum = profile.alpha() + profile.beta();
            assertThat(sum).as("alpha + beta for %s", profile)
                    .isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
        }
    }
}
