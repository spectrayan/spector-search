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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;

/**
 * Utility to bind common JVM and system metrics to a Micrometer {@link MeterRegistry}.
 * Useful for standalone deployments (like Spector Server) that don't have
 * Spring Boot's automatic Actuator binder support.
 */
public final class SpectorJvmMetrics {

    private SpectorJvmMetrics() {
        // Utility class
    }

    /**
     * Binds JVM Memory, GC, Thread, and Processor/System metrics to the given registry.
     *
     * @param registry the Micrometer registry to bind metrics to
     */
    public static void bind(MeterRegistry registry) {
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
    }
}
