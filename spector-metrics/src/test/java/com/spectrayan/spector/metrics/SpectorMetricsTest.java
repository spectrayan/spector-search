package com.spectrayan.spector.metrics;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpectorMetrics}.
 */
class SpectorMetricsTest {

    @Test
    void defaultRegistryIsSimpleMeterRegistry() {
        MeterRegistry registry = SpectorMetrics.registry();
        assertThat(registry).isNotNull();
        assertThat(registry).isInstanceOf(SimpleMeterRegistry.class);
    }

    @Test
    void initSwapsRegistry() {
        MeterRegistry newRegistry = new SimpleMeterRegistry();
        SpectorMetrics.init(newRegistry);
        assertThat(SpectorMetrics.registry()).isSameAs(newRegistry);
    }

    @Test
    void initThrowsOnNull() {
        assertThatThrownBy(() -> SpectorMetrics.init(null))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("MeterRegistry must not be null");
    }
}
