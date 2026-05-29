package com.spectrayan.spector.engine;


import com.spectrayan.spector.config.SpectorConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.core.quantization.QuantizationType;

/**
 * Unit tests for SpectorConfig rescore/oversampling factor support.
 *
 * Validates Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
 */
class SpectorConfigRescoreTest {

    @Test
    void withRescore_setsOversamplingFactor() {
        SpectorConfig config = SpectorConfig.DEFAULT.withRescore(5);
        assertThat(config.oversamplingFactor()).isEqualTo(5);
    }

    @Test
    void withRescore_factorOfOne_isValid() {
        SpectorConfig config = SpectorConfig.DEFAULT.withRescore(1);
        assertThat(config.oversamplingFactor()).isEqualTo(1);
    }

    @Test
    void withRescore_rejectsZero() {
        assertThatThrownBy(() -> SpectorConfig.DEFAULT.withRescore(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withRescore_rejectsNegative() {
        assertThatThrownBy(() -> SpectorConfig.DEFAULT.withRescore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectiveOversamplingFactor_int4Default() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withQuantization(QuantizationType.SCALAR_INT4);
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(3);
    }

    @Test
    void effectiveOversamplingFactor_int2Default() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withQuantization(QuantizationType.SCALAR_INT2);
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(5);
    }

    @Test
    void effectiveOversamplingFactor_int8Default() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withQuantization(QuantizationType.SCALAR_INT8);
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(1);
    }

    @Test
    void effectiveOversamplingFactor_noneDefault() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withQuantization(QuantizationType.NONE);
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(1);
    }

    @Test
    void effectiveOversamplingFactor_explicitOverridesDefault() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withQuantization(QuantizationType.SCALAR_INT4)
                .withRescore(7);
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(7);
    }

    @Test
    void effectiveOversamplingFactor_explicitOneOverridesInt4Default() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withQuantization(QuantizationType.SCALAR_INT4)
                .withRescore(1);
        // Explicit 1 means skip rescore
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(1);
    }

    @Test
    void defaultConfig_oversamplingFactorIsZero() {
        assertThat(SpectorConfig.DEFAULT.oversamplingFactor()).isEqualTo(0);
    }

    @Test
    void withRescore_preservesOtherFields() {
        SpectorConfig base = SpectorConfig.DEFAULT
                .withDimensions(128)
                .withCapacity(50_000)
                .withQuantization(QuantizationType.SCALAR_INT4);

        SpectorConfig rescored = base.withRescore(4);

        assertThat(rescored.dimensions()).isEqualTo(128);
        assertThat(rescored.capacity()).isEqualTo(50_000);
        assertThat(rescored.quantization()).isEqualTo(QuantizationType.SCALAR_INT4);
        assertThat(rescored.oversamplingFactor()).isEqualTo(4);
    }
}
