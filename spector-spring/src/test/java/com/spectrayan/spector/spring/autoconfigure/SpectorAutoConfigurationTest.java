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
package com.spectrayan.spector.spring.autoconfigure;

import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.metrics.MeteredSpectorEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration and unit tests for {@link SpectorAutoConfiguration} using {@link ApplicationContextRunner}.
 */
class SpectorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpectorAutoConfiguration.class));

    @Test
    void defaultConfiguration_createsEngineBean() {
        this.contextRunner
                .withPropertyValues("spector.engine.dimensions=384")
                .run(context -> {
                    assertThat(context).hasSingleBean(SpectorEngine.class);
                    SpectorEngine engine = context.getBean(SpectorEngine.class);
                    assertThat(engine.config().dimensions()).isEqualTo(384);
                });
    }

    @Test
    void withMeterRegistry_wrapsEngineWithMeteredDecorator() {
        this.contextRunner
                .withUserConfiguration(TestMeterRegistryConfiguration.class)
                .withPropertyValues("spector.engine.dimensions=384", "spector.metrics.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(SpectorEngine.class);
                    SpectorEngine engine = context.getBean(SpectorEngine.class);
                    assertThat(engine).isInstanceOf(MeteredSpectorEngine.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestMeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
