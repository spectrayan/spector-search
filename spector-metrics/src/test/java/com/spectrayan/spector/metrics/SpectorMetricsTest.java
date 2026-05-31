/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
