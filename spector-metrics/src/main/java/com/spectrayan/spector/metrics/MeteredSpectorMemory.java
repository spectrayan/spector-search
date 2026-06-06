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

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.events.MemorySnapshotTelemetry;
import com.spectrayan.spector.events.ReflectCycleTelemetry;
import com.spectrayan.spector.events.TelemetryScope;
import com.spectrayan.spector.memory.CognitiveProfile;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.ReflectReport;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.inhibition.SuppressionSet;
import com.spectrayan.spector.memory.metamemory.MemoryInsight;
import com.spectrayan.spector.memory.neurodivergent.LateralEvaluator;
import com.spectrayan.spector.memory.pipeline.CognitiveIngestionTarget;
import com.spectrayan.spector.memory.pipeline.RecallPipeline;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.prospective.Reminder;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.temporal.TemporalChain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Metered decorator for {@link SpectorMemory}.
 *
 * <p>Wraps a delegate memory and records Micrometer metrics for all
 * core cognitive operations (remember, recall, forget, reinforce, reflect).
 * Subsystem accessors and lightweight operations pass through without
 * instrumentation overhead.</p>
 *
 * <h3>Metrics Registered</h3>
 * <table>
 *   <tr><th>Name</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>{@code spector.memory.recall.duration}</td><td>Timer</td><td>Cognitive recall latency</td></tr>
 *   <tr><td>{@code spector.memory.recall.total}</td><td>Counter</td><td>Total recall queries</td></tr>
 *   <tr><td>{@code spector.memory.remember.total}</td><td>Counter</td><td>Total memories stored</td></tr>
 *   <tr><td>{@code spector.memory.reinforce.total}</td><td>Counter</td><td>Total reinforcement events</td></tr>
 *   <tr><td>{@code spector.memory.forget.total}</td><td>Counter</td><td>Total forget events</td></tr>
 *   <tr><td>{@code spector.memory.reflect.duration}</td><td>Timer</td><td>Reflection cycle latency</td></tr>
 *   <tr><td>{@code spector.memory.count}</td><td>Gauge</td><td>Total memory count</td></tr>
 * </table>
 *
 * @see SpectorMemory
 */
public class MeteredSpectorMemory implements SpectorMemory {

    public static final String METRIC_RECALL_DURATION = "spector.memory.recall.duration";
    public static final String METRIC_REFLECT_DURATION = "spector.memory.reflect.duration";
    public static final String METRIC_RECALL_TOTAL = "spector.memory.recall.total";
    public static final String METRIC_REMEMBER_TOTAL = "spector.memory.remember.total";
    public static final String METRIC_REINFORCE_TOTAL = "spector.memory.reinforce.total";
    public static final String METRIC_FORGET_TOTAL = "spector.memory.forget.total";
    public static final String METRIC_SUPPRESS_TOTAL = "spector.memory.suppress.total";
    public static final String METRIC_COUNT = "spector.memory.count";

    private final SpectorMemory delegate;

    // ── Timers ──
    private final Timer recallTimer;
    private final Timer reflectTimer;

    // ── Counters ──
    private final Counter recallCounter;
    private final Counter rememberCounter;
    private final Counter reinforceCounter;
    private final Counter forgetCounter;
    private final Counter suppressCounter;

    /**
     * Creates a metered memory wrapping the given delegate.
     *
     * @param delegate the actual memory implementation
     * @param registry the meter registry to register metrics with
     */
    public MeteredSpectorMemory(SpectorMemory delegate, MeterRegistry registry) {
        this.delegate = delegate;

        // Timers with microsecond-precision percentile histograms
        this.recallTimer = Timer.builder(METRIC_RECALL_DURATION)
                .description("Time spent in cognitive recall")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.reflectTimer = Timer.builder(METRIC_REFLECT_DURATION)
                .description("Time spent in sleep consolidation (reflection)")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        // Counters
        this.recallCounter = Counter.builder(METRIC_RECALL_TOTAL)
                .description("Total recall queries")
                .register(registry);
        this.rememberCounter = Counter.builder(METRIC_REMEMBER_TOTAL)
                .description("Total memories ingested")
                .register(registry);
        this.reinforceCounter = Counter.builder(METRIC_REINFORCE_TOTAL)
                .description("Total reinforcement events")
                .register(registry);
        this.forgetCounter = Counter.builder(METRIC_FORGET_TOTAL)
                .description("Total memories forgotten")
                .register(registry);
        this.suppressCounter = Counter.builder(METRIC_SUPPRESS_TOTAL)
                .description("Total memories suppressed")
                .register(registry);

        // Gauges
        Gauge.builder(METRIC_COUNT, delegate, SpectorMemory::totalMemories)
                .description("Total number of memories across all tiers")
                .register(registry);

        // Soft & Hard Page Fault Gauges (Linux container tracking)
        Gauge.builder("spector.memory.page.faults", () -> readPageFaults()[0])
                .tag("type", "soft")
                .description("Soft page faults (minor faults) on Linux")
                .register(registry);

        Gauge.builder("spector.memory.page.faults", () -> readPageFaults()[1])
                .tag("type", "hard")
                .description("Hard page faults (major faults) on Linux")
                .register(registry);

        // Pinned Bytes Gauge (RAM usage verification)
        Gauge.builder("spector.memory.pinned.bytes", com.spectrayan.spector.commons.concurrent.MemoryPinning::pinnedBytes)
                .description("Total off-heap memory bytes pinned in RAM")
                .register(registry);
    }

    /**
     * Returns the underlying delegate memory.
     */
    public SpectorMemory unwrap() {
        return delegate;
    }

    // ══════════════════════════════════════════════════════════════
    // INGESTION TARGET (pass-through)
    // ══════════════════════════════════════════════════════════════

    @Override
    public CognitiveIngestionTarget target() { return delegate.target(); }

    // ══════════════════════════════════════════════════════════════
    // CORE API (metered)
    // ══════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source, String... tags) {
        rememberCounter.increment();
        return delegate.remember(id, text, type, source, tags);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source,
                                              com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                              String... tags) {
        rememberCounter.increment();
        return delegate.remember(id, text, type, source, hints, tags);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              String... tags) {
        rememberCounter.increment();
        return delegate.remember(id, text, type, tags);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source,
                                              com.spectrayan.spector.memory.IngestionContext context,
                                              String... tags) {
        rememberCounter.increment();
        return delegate.remember(id, text, type, source, context, tags);
    }

    @Override
    public List<CognitiveResult> recall(String queryText, RecallOptions options) {
        recallCounter.increment();
        return recallTimer.record(() -> delegate.recall(queryText, options));
    }

    @Override
    public List<CognitiveResult> recall(String queryText, CognitiveProfile profile) {
        recallCounter.increment();
        return recallTimer.record(() -> delegate.recall(queryText, profile));
    }

    @Override
    public List<CognitiveResult> recall(String queryText) {
        recallCounter.increment();
        return recallTimer.record(() -> delegate.recall(queryText));
    }

    @Override
    public void forget(String id) {
        forgetCounter.increment();
        delegate.forget(id);
    }

    @Override
    public ReflectReport reflect() {
        // Capture pre-reflect snapshot
        String cycleId = java.util.UUID.randomUUID().toString();
        TelemetryScope.publish(captureMemorySnapshot("pre-reflect", cycleId));

        ReflectReport report = reflectTimer.record(() -> delegate.reflect());

        // Capture post-reflect snapshot
        TelemetryScope.publish(captureMemorySnapshot("post-reflect", cycleId));

        // Publish reflect cycle summary
        TelemetryScope.publish(new ReflectCycleTelemetry(
                report.consolidatedCount(),
                report.tombstonedCount(),
                0.0, // decayFactor — available from config if needed
                report.duration().toMillis()));

        return report;
    }

    /** Captures a memory snapshot for telemetry. */
    private MemorySnapshotTelemetry captureMemorySnapshot(String phase, String cycleId) {
        return new MemorySnapshotTelemetry(
                phase, cycleId,
                delegate.hebbianGraph() != null ? delegate.hebbianGraph().totalEdges() : 0,
                delegate.temporalChain() != null ? delegate.temporalChain().capacity() : 0,
                delegate.entityGraph() != null ? delegate.entityGraph().entityCount() : 0,
                delegate.entityGraph() != null ? delegate.entityGraph().edgeCount() : 0,
                0L, // offHeapBytes — from Micrometer gauge
                0,  // tombstoneCount — TBD
                delegate.coActivation() != null ? delegate.coActivation().pairCount() : 0,
                0); // stdpEdges — TBD
    }

    // ══════════════════════════════════════════════════════════════
    // EXTENDED API (metered where meaningful)
    // ══════════════════════════════════════════════════════════════

    @Override
    public void reinforce(String memoryId, byte valence) {
        reinforceCounter.increment();
        delegate.reinforce(memoryId, valence);
    }

    @Override
    public void suppress(String memoryId, String reason) {
        suppressCounter.increment();
        delegate.suppress(memoryId, reason);
    }

    @Override
    public void suppress(String memoryId) {
        suppressCounter.increment();
        delegate.suppress(memoryId);
    }

    @Override
    public void unsuppress(String memoryId) { delegate.unsuppress(memoryId); }

    @Override
    public void markResolved(String memoryId) { delegate.markResolved(memoryId); }

    @Override
    public void markUnresolved(String memoryId) { delegate.markUnresolved(memoryId); }

    @Override
    public MemoryInsight introspect(String topic) {
        return delegate.introspect(topic);
    }

    @Override
    public com.spectrayan.spector.memory.WhyNotExplanation whyNot(
            String memoryId, String query, RecallOptions options) {
        return delegate.whyNot(memoryId, query, options);
    }

    // ══════════════════════════════════════════════════════════════
    // PROSPECTIVE / SCRATCHPAD / STATS (pass-through)
    // ══════════════════════════════════════════════════════════════

    @Override
    public Reminder scheduleReminder(String text, Instant triggerAt, String... tags) {
        return delegate.scheduleReminder(text, triggerAt, tags);
    }

    @Override
    public Reminder scheduleReminder(String text, Duration delay, String... tags) {
        return delegate.scheduleReminder(text, delay, tags);
    }

    @Override
    public CompletableFuture<Void> scratchpad(String text) {
        return delegate.scratchpad(text);
    }

    @Override public int totalMemories() { return delegate.totalMemories(); }
    @Override public int memoryCount(MemoryType type) { return delegate.memoryCount(type); }
    @Override public int decay(Duration olderThan, float factor) { return delegate.decay(olderThan, factor); }

    // ══════════════════════════════════════════════════════════════
    // SUBSYSTEM ACCESSORS (pass-through)
    // ══════════════════════════════════════════════════════════════

    @Override public CoActivationTracker coActivation() { return delegate.coActivation(); }
    @Override public MemoryWal wal() { return delegate.wal(); }
    @Override public ProspectiveScheduler prospective() { return delegate.prospective(); }
    @Override public SuppressionSet suppression() { return delegate.suppression(); }
    @Override public HabituationPenalty habituation() { return delegate.habituation(); }
    @Override public ScalarQuantizer quantizer() { return delegate.quantizer(); }
    @Override public CognitiveIngestionTarget cognitiveTarget() { return delegate.cognitiveTarget(); }
    @Override public RecallPipeline recallPipeline() { return delegate.recallPipeline(); }
    @Override public TierRouter tierRouter() { return delegate.tierRouter(); }
    @Override public MemoryIndex index() { return delegate.index(); }
    @Override public LateralEvaluator lateralEvaluator() { return delegate.lateralEvaluator(); }
    @Override public HebbianGraph hebbianGraph() { return delegate.hebbianGraph(); }
    @Override public TemporalChain temporalChain() { return delegate.temporalChain(); }
    @Override public EntityGraph entityGraph() { return delegate.entityGraph(); }

    // ── Lifecycle ──

    @Override
    public void close() {
        delegate.close();
    }

    private static long[] readPageFaults() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("/proc/self/stat");
            if (java.nio.file.Files.exists(path)) {
                String content = java.nio.file.Files.readString(path);
                int lastParen = content.lastIndexOf(')');
                if (lastParen != -1 && lastParen + 2 < content.length()) {
                    String rest = content.substring(lastParen + 2);
                    String[] tokens = rest.split("\\s+");
                    if (tokens.length > 9) {
                        long soft = Long.parseLong(tokens[7]);
                        long hard = Long.parseLong(tokens[9]);
                        return new long[]{soft, hard};
                    }
                }
            }
        } catch (Exception e) {
            // safe fallback
        }
        return new long[]{0L, 0L};
    }
}
