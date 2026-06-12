/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.memory;

import static org.assertj.core.api.Assertions.*;

import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.memory.model.CognitiveProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CognitiveProfileConfig} — presets, validation,
 * config parsing, edge cases.
 */
@DisplayName("CognitiveProfileConfig")
class CognitiveProfileConfigTest {

    // ══════════════════════════════════════════════════════════════
    // Presets
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("presets")
    class PresetTests {

        @Test
        @DisplayName("allEnabled includes all CognitiveProfile values")
        void allEnabled() {
            var config = CognitiveProfileConfig.allEnabled();
            for (CognitiveProfile p : CognitiveProfile.values()) {
                assertThat(config.isEnabled(p))
                        .as("Profile %s should be enabled", p)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("coreOnly includes exactly 5 core profiles")
        void coreOnly() {
            var config = CognitiveProfileConfig.coreOnly();
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.EXPLORING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.DEBUGGING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.RECALLING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.CRITICAL)).isTrue();
            assertThat(config.enabledProfiles()).hasSize(5);
        }

        @Test
        @DisplayName("withNeurodivergent includes core + 3 neuro profiles")
        void withNeurodivergent() {
            var config = CognitiveProfileConfig.withNeurodivergent();
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.SYSTEMATIZER)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.DIVERGENT)).isTrue();
            assertThat(config.enabledProfiles()).hasSize(8);
        }

        @Test
        @DisplayName("only() always includes BALANCED")
        void onlyAlwaysIncludesBalanced() {
            var config = CognitiveProfileConfig.only(CognitiveProfile.DEBUGGING);
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.DEBUGGING)).isTrue();
            assertThat(config.enabledProfiles()).hasSize(2);
        }

        @Test
        @DisplayName("only() with null values ignores them")
        void onlyIgnoresNulls() {
            var config = CognitiveProfileConfig.only(null, CognitiveProfile.DEBUGGING, null);
            assertThat(config.enabledProfiles()).hasSize(2);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Validation
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validate")
    class ValidateTests {

        @Test
        @DisplayName("returns enabled profile as-is")
        void enabledProfile() {
            var config = CognitiveProfileConfig.allEnabled();
            assertThat(config.validate(CognitiveProfile.HYPERFOCUS))
                    .isEqualTo(CognitiveProfile.HYPERFOCUS);
        }

        @Test
        @DisplayName("returns BALANCED for disabled profile")
        void disabledProfile() {
            var config = CognitiveProfileConfig.coreOnly();
            assertThat(config.validate(CognitiveProfile.HYPERFOCUS))
                    .isEqualTo(CognitiveProfile.BALANCED);
        }

        @Test
        @DisplayName("returns BALANCED for null profile")
        void nullProfile() {
            var config = CognitiveProfileConfig.allEnabled();
            assertThat(config.validate(null))
                    .isEqualTo(CognitiveProfile.BALANCED);
        }
    }

    @Nested
    @DisplayName("requireEnabled")
    class RequireEnabledTests {

        @Test
        @DisplayName("returns profile when enabled")
        void enabledProfile() {
            var config = CognitiveProfileConfig.allEnabled();
            assertThat(config.requireEnabled(CognitiveProfile.DEBUGGING))
                    .isEqualTo(CognitiveProfile.DEBUGGING);
        }

        @Test
        @DisplayName("throws when profile is disabled")
        void disabledProfile() {
            var config = CognitiveProfileConfig.coreOnly();
            assertThatThrownBy(() -> config.requireEnabled(CognitiveProfile.HYPERFOCUS))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test
        @DisplayName("throws for null profile")
        void nullProfile() {
            var config = CognitiveProfileConfig.allEnabled();
            assertThatThrownBy(() -> config.requireEnabled(null))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Config value parsing
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fromConfigValue")
    class ConfigValueTests {

        @ParameterizedTest
        @ValueSource(strings = {"ALL", "all", "All"})
        @DisplayName("'ALL' variants return all profiles")
        void allVariants(String value) {
            var config = CognitiveProfileConfig.fromConfigValue(value);
            assertThat(config.enabledProfiles()).hasSize(CognitiveProfile.values().length);
        }

        @Test
        @DisplayName("'CORE_ONLY' returns core profiles")
        void coreOnlyString() {
            var config = CognitiveProfileConfig.fromConfigValue("CORE_ONLY");
            assertThat(config.enabledProfiles()).hasSize(5);
        }

        @Test
        @DisplayName("'WITH_NEURODIVERGENT' returns core + neuro")
        void withNeuroString() {
            var config = CognitiveProfileConfig.fromConfigValue("WITH_NEURODIVERGENT");
            assertThat(config.enabledProfiles()).hasSize(8);
        }

        @Test
        @DisplayName("null defaults to all enabled")
        void nullDefaults() {
            var config = CognitiveProfileConfig.fromConfigValue(null);
            assertThat(config.enabledProfiles()).hasSize(CognitiveProfile.values().length);
        }

        @Test
        @DisplayName("blank defaults to all enabled")
        void blankDefaults() {
            var config = CognitiveProfileConfig.fromConfigValue("  ");
            assertThat(config.enabledProfiles()).hasSize(CognitiveProfile.values().length);
        }

        @Test
        @DisplayName("CSV list parses individual profiles")
        void csvParsing() {
            var config = CognitiveProfileConfig.fromConfigValue("DEBUGGING,HYPERFOCUS");
            assertThat(config.isEnabled(CognitiveProfile.BALANCED)).isTrue(); // always included
            assertThat(config.isEnabled(CognitiveProfile.DEBUGGING)).isTrue();
            assertThat(config.isEnabled(CognitiveProfile.HYPERFOCUS)).isTrue();
            assertThat(config.enabledProfiles()).hasSize(3);
        }

        @Test
        @DisplayName("unknown profile in CSV throws")
        void unknownInCsv() {
            assertThatThrownBy(() -> CognitiveProfileConfig.fromConfigValue("BALANCED,NONEXISTENT"))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // toString
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("toString includes enabled profiles")
    void toStringIncludesProfiles() {
        var config = CognitiveProfileConfig.coreOnly();
        assertThat(config.toString())
                .startsWith("CognitiveProfileConfig{enabled=")
                .contains("BALANCED");
    }

    // ══════════════════════════════════════════════════════════════
    // Immutability
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("enabledProfiles returns unmodifiable set")
    void unmodifiableSet() {
        var config = CognitiveProfileConfig.allEnabled();
        assertThatThrownBy(() -> config.enabledProfiles().add(CognitiveProfile.BALANCED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
