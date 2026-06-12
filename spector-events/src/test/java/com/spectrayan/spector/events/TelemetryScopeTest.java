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
package com.spectrayan.spector.events;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TelemetryScope} — ScopedValue-based telemetry bus carrier.
 */
@DisplayName("TelemetryScope")
class TelemetryScopeTest {

    @Test
    @DisplayName("isActive returns false when no bus is bound")
    void isActiveReturnsFalseWhenUnbound() {
        assertThat(TelemetryScope.isActive()).isFalse();
    }

    @Test
    @DisplayName("publish is a no-op when no bus is bound")
    void publishIsNoOpWhenUnbound() {
        // Should not throw even when no bus is bound
        assertThatCode(() ->
                TelemetryScope.publish(new SimdKernelTelemetry("cosine", 16, 1, 100))
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("isActive returns true when bus is bound via ScopedValue")
    void isActiveReturnsTrueWhenBound() throws Exception {
        TelemetryBus bus = new TelemetryBus();
        boolean active = ScopedValue.where(TelemetryScope.BUS, bus)
                .call(TelemetryScope::isActive);
        assertThat(active).isTrue();
        bus.close();
    }

    @Test
    @DisplayName("publish delivers event when bus is bound")
    void publishDeliversWhenBound() throws Exception {
        TelemetryBus bus = new TelemetryBus();
        List<TelemetryEvent> received = new ArrayList<>();
        bus.subscribe(received::add);

        var event = new SimdKernelTelemetry("cosine", 16, 1000, 5000);
        ScopedValue.where(TelemetryScope.BUS, bus).run(() -> {
            TelemetryScope.publish(event);
        });

        assertThat(received).containsExactly(event);
        bus.close();
    }

    @Test
    @DisplayName("bus is not bound after ScopedValue scope exits")
    void busUnboundAfterScopeExits() throws Exception {
        TelemetryBus bus = new TelemetryBus();
        boolean activeDuring = ScopedValue.where(TelemetryScope.BUS, bus)
                .call(TelemetryScope::isActive);
        assertThat(activeDuring).isTrue();

        // After scope exits, bus should no longer be bound
        assertThat(TelemetryScope.isActive()).isFalse();
        bus.close();
    }

    @Test
    @DisplayName("nested scopes use innermost bus")
    void nestedScopesUseInnermostBus() throws Exception {
        TelemetryBus outerBus = new TelemetryBus();
        TelemetryBus innerBus = new TelemetryBus();
        List<TelemetryEvent> outerReceived = new ArrayList<>();
        List<TelemetryEvent> innerReceived = new ArrayList<>();
        outerBus.subscribe(outerReceived::add);
        innerBus.subscribe(innerReceived::add);

        var event = new SimdKernelTelemetry("inner", 16, 1, 100);
        ScopedValue.where(TelemetryScope.BUS, outerBus).run(() -> {
            ScopedValue.where(TelemetryScope.BUS, innerBus).run(() -> {
                TelemetryScope.publish(event);
            });
        });

        assertThat(innerReceived).containsExactly(event);
        assertThat(outerReceived).isEmpty();
        outerBus.close();
        innerBus.close();
    }
}
