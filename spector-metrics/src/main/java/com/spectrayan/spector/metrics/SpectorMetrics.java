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
import com.spectrayan.spector.commons.error.ErrorCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Global {@link MeterRegistry} holder for Spector observability.
 *
 * <p>By default, uses a {@link SimpleMeterRegistry} which silently discards
 * all metrics — zero overhead when observability is not configured.
 * Call {@link #init(MeterRegistry)} at startup to wire a real registry
 * (Prometheus, Datadog, JMX, etc.).</p>
 *
 * <h3>Standalone Usage (Javalin / MCP)</h3>
 * <pre>{@code
 *   var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
 *   SpectorMetrics.init(registry);
 * }</pre>
 *
 * <h3>Spring Boot Usage</h3>
 * <p>Spring auto-configuration calls {@code SpectorMetrics.init(springRegistry)}
 * automatically — no user action required.</p>
 */
public final class SpectorMetrics {

    private static volatile MeterRegistry registry = new SimpleMeterRegistry();

    private SpectorMetrics() {}

    /**
     * Initializes the global meter registry.
     *
     * <p>Should be called once at application startup, before any metrics
     * are recorded. Thread-safe via volatile write.</p>
     *
     * @param registry the meter registry to use
     * @throws SpectorValidationException if registry is null
     */
    public static void init(MeterRegistry registry) {
        if (registry == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "MeterRegistry");
        }
        SpectorMetrics.registry = registry;
    }

    /**
     * Returns the current meter registry.
     *
     * @return the active meter registry (never null)
     */
    public static MeterRegistry registry() {
        return registry;
    }
}
