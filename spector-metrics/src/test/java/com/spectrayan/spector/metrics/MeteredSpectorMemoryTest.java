package com.spectrayan.spector.metrics;

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.memory.*;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.inhibition.SuppressionSet;
import com.spectrayan.spector.memory.metamemory.MemoryInsight;
import com.spectrayan.spector.memory.neurodivergent.LateralEvaluator;
import com.spectrayan.spector.memory.pipeline.CognitiveIngestionTarget;
import com.spectrayan.spector.memory.pipeline.RecallPipeline;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.prospective.Reminder;
import com.spectrayan.spector.memory.sync.MemoryWal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MeteredSpectorMemory}.
 */
class MeteredSpectorMemoryTest {

    @Test
    void recallRecordsMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SpectorMemory stub = new DummySpectorMemory() {
            @Override
            public List<CognitiveResult> recall(String queryText) {
                return new ArrayList<>();
            }
        };

        MeteredSpectorMemory metered = new MeteredSpectorMemory(stub, registry);
        List<CognitiveResult> results = metered.recall("hello");

        assertThat(results).isNotNull();
        assertThat(registry.get(MeteredSpectorMemory.METRIC_RECALL_TOTAL).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MeteredSpectorMemory.METRIC_RECALL_DURATION).timer().count()).isEqualTo(1L);
    }

    @Test
    void rememberRecordsMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SpectorMemory stub = new DummySpectorMemory();

        MeteredSpectorMemory metered = new MeteredSpectorMemory(stub, registry);
        metered.remember("id-1", "content-1", MemoryType.EPISODIC, MemorySource.USER_STATED, "tag");

        assertThat(registry.get(MeteredSpectorMemory.METRIC_REMEMBER_TOTAL).counter().count()).isEqualTo(1.0);
    }

    @Test
    void observabilityMetricsRegistered() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SpectorMemory stub = new DummySpectorMemory();

        new MeteredSpectorMemory(stub, registry);

        assertThat(registry.find("spector.memory.page.faults").tag("type", "soft").gauge()).isNotNull();
        assertThat(registry.find("spector.memory.page.faults").tag("type", "hard").gauge()).isNotNull();
        assertThat(registry.find("spector.memory.pinned.bytes").gauge()).isNotNull();
    }

    static class DummySpectorMemory implements SpectorMemory {
        @Override public CognitiveIngestionTarget target() { return null; }
        @Override public CompletableFuture<Void> remember(String id, String text, MemoryType type, MemorySource source, String... tags) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> remember(String id, String text, MemoryType type, String... tags) { return CompletableFuture.completedFuture(null); }
        @Override public List<CognitiveResult> recall(String queryText, RecallOptions options) { return null; }
        @Override public List<CognitiveResult> recall(String queryText, CognitiveProfile profile) { return null; }
        @Override public List<CognitiveResult> recall(String queryText) { return null; }
        @Override public void forget(String id) {}
        @Override public ReflectReport reflect() { return null; }
        @Override public void reinforce(String memoryId, byte valence) {}
        @Override public void suppress(String memoryId, String reason) {}
        @Override public void suppress(String memoryId) {}
        @Override public void unsuppress(String memoryId) {}
        @Override public void markResolved(String memoryId) {}
        @Override public void markUnresolved(String memoryId) {}
        @Override public MemoryInsight introspect(String topic) { return null; }
        @Override public Reminder scheduleReminder(String text, Instant triggerAt, String... tags) { return null; }
        @Override public Reminder scheduleReminder(String text, Duration delay, String... tags) { return null; }
        @Override public CompletableFuture<Void> scratchpad(String text) { return CompletableFuture.completedFuture(null); }
        @Override public int totalMemories() { return 0; }
        @Override public int memoryCount(MemoryType type) { return 0; }
        @Override public int decay(Duration olderThan, float factor) { return 0; }
        @Override public CoActivationTracker coActivation() { return null; }
        @Override public MemoryWal wal() { return null; }
        @Override public ProspectiveScheduler prospective() { return null; }
        @Override public SuppressionSet suppression() { return null; }
        @Override public HabituationPenalty habituation() { return null; }
        @Override public ScalarQuantizer quantizer() { return null; }
        @Override public CognitiveIngestionTarget cognitiveTarget() { return null; }
        @Override public RecallPipeline recallPipeline() { return null; }
        @Override public TierRouter tierRouter() { return null; }
        @Override public MemoryIndex index() { return null; }
        @Override public LateralEvaluator lateralEvaluator() { return null; }
        @Override public void close() {}
    }
}
