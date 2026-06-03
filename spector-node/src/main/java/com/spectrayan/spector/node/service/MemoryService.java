package com.spectrayan.spector.node.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.ReflectReport;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;
import com.spectrayan.spector.node.api.dto.MemoryStatusDto;
import com.spectrayan.spector.node.event.SpectorCortexMemorySnapshotEvent;
import com.spectrayan.spector.node.event.SpectorCortexReflectCycleEvent;
import com.spectrayan.spector.node.event.SpectorEventBus;

/**
 * Service facade that wraps {@link SpectorMemory} and emits cortex
 * dashboard events after key operations.
 *
 * <p>Sits at the spector-node layer so it can access both the memory
 * subsystem (via spector-memory dependency) and the event bus (via
 * spector-node). This avoids polluting the memory module with event
 * bus coupling.</p>
 *
 * <p>Latency is <em>not</em> manually timed here — the Micrometer
 * {@code MeteredSpectorMemory} decorator handles that. This service
 * uses the duration from {@link ReflectReport#duration()} which is
 * computed inside the reflect daemon itself.</p>
 *
 * <h3>Emitted Events</h3>
 * <ul>
 *   <li>{@link SpectorCortexReflectCycleEvent} — after each reflect() call</li>
 *   <li>{@link SpectorCortexMemorySnapshotEvent} — pre/post reflect snapshots for diff view</li>
 * </ul>
 */
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final SpectorMemory memory;
    private final SpectorEventBus eventBus;
    private final String nodeId;

    public MemoryService(SpectorMemory memory, SpectorEventBus eventBus, String nodeId) {
        this.memory = memory;
        this.eventBus = eventBus;
        this.nodeId = nodeId;
    }

    /**
     * Triggers a reflection cycle and emits cortex events.
     *
     * <p>Emits a pre-reflect snapshot, runs the consolidation cycle,
     * then emits a post-reflect snapshot and a reflect cycle summary.
     * The pre/post snapshots share a {@code reflectCycleId} so the
     * dashboard can compute deltas.</p>
     *
     * <p>Uses duration from the {@link ReflectReport} itself rather than
     * manually timing — Micrometer's {@code MeteredSpectorMemory} handles
     * latency recording separately.</p>
     *
     * @return the reflect report
     */
    public ReflectReport reflect() {
        String reflectCycleId = UUID.randomUUID().toString();

        // ── Pre-reflect snapshot ──
        eventBus.publish(captureSnapshot("pre-reflect", reflectCycleId));

        // ── Run consolidation ──
        ReflectReport report = memory.reflect();

        // ── Post-reflect snapshot ──
        eventBus.publish(captureSnapshot("post-reflect", reflectCycleId));

        // ── Reflect cycle summary event ──
        long durationMs = report.duration().toMillis();
        eventBus.publish(new SpectorCortexReflectCycleEvent(
                nodeId, Instant.now(),
                report.tombstonedCount(),
                report.temporalPrunedCount(),
                0.9, // decay factor used in reflect()
                durationMs));

        return report;
    }

    /**
     * Captures a memory snapshot for the diff view.
     *
     * <p>Reads current counts from the memory subsystem. Graph-level
     * stats (edges, nodes) are placeholders until tier-level APIs
     * are exposed by SpectorMemory.</p>
     */
    private SpectorCortexMemorySnapshotEvent captureSnapshot(String phase, String reflectCycleId) {
        int totalCount = memory.totalMemories();
        return new SpectorCortexMemorySnapshotEvent(
                nodeId, Instant.now(),
                phase, reflectCycleId,
                0,              // hebbianEdgeCount — TBD when graph stats API available
                0,              // temporalLinkCount — TBD
                0,              // entityNodeCount — TBD
                0,              // entityEdgeCount — TBD
                0L,             // offHeapBytes — TBD (from PanamaMemoryDetector)
                0,              // tombstoneCount — TBD
                0,              // coActivationPairs — TBD
                0);             // stdpEdges — TBD
    }

    /** Returns the underlying memory instance. */
    public SpectorMemory memory() { return memory; }

    /** Stores a memory with full cognitive metadata. */
    public CompletableFuture<Void> remember(String id, String text, MemoryType tier,
                                           MemorySource source, IngestionHints hints,
                                           String... tags) {
        return memory.remember(id, text, tier, source, hints, tags);
    }

    /** Performs cognitive recall. */
    public List<CognitiveResult> recall(String query, RecallOptions options) {
        return memory.recall(query, options);
    }

    /** Tombstones a memory by ID. */
    public void forget(String id) {
        memory.forget(id);
    }

    /** Reports outcome feedback for a memory. */
    public void reinforce(String id, byte valence) {
        memory.reinforce(id, valence);
    }

    /** Suppresses a memory from future recall. */
    public void suppress(String id, String reason) {
        if (reason != null && !reason.isBlank()) {
            memory.suppress(id, reason);
        } else {
            memory.suppress(id);
        }
    }

    /** Unsuppresses a memory. */
    public void unsuppress(String id) {
        memory.unsuppress(id);
    }

    /** Marks a memory as resolved. */
    public void markResolved(String id) {
        memory.markResolved(id);
    }

    /** Marks a memory as unresolved. */
    public void markUnresolved(String id) {
        memory.markUnresolved(id);
    }

    /** Returns comprehensive stats and status of the cognitive memory system. */
    public MemoryStatusDto getStatus() {
        int total = memory.totalMemories();
        var counts = Map.of(
                "WORKING", memory.memoryCount(MemoryType.WORKING),
                "EPISODIC", memory.memoryCount(MemoryType.EPISODIC),
                "SEMANTIC", memory.memoryCount(MemoryType.SEMANTIC),
                "PROCEDURAL", memory.memoryCount(MemoryType.PROCEDURAL)
        );
        int hebbian = memory.hebbianGraph() != null ? memory.hebbianGraph().totalEdges() : 0;
        int entityNodes = memory.entityGraph() != null ? memory.entityGraph().entityCount() : 0;
        int entityEdges = memory.entityGraph() != null ? memory.entityGraph().edgeCount() : 0;

        int temporalLinks = 0;
        if (memory.temporalChain() != null) {
            int cap = memory.temporalChain().capacity();
            for (int i = 0; i < cap; i++) {
                if (memory.temporalChain().isLinked(i)) {
                    temporalLinks++;
                }
            }
        }

        return new MemoryStatusDto(total, counts, hebbian, temporalLinks, entityNodes, entityEdges);
    }

    /** Introspects the agent's knowledge about a topic. */
    public com.spectrayan.spector.memory.metamemory.MemoryInsight introspect(String topic) {
        return memory.introspect(topic);
    }

    /** Schedules a prospective memory reminder. */
    public com.spectrayan.spector.memory.prospective.Reminder scheduleReminder(
            String text, java.time.Duration delay, String... tags) {
        return memory.scheduleReminder(text, delay, tags);
    }

    /** Stores a note in working memory scratchpad. */
    public java.util.concurrent.CompletableFuture<Void> scratchpad(String text) {
        return memory.scratchpad(text);
    }

    /** Explains why a specific memory was not returned for a query. */
    public com.spectrayan.spector.memory.WhyNotExplanation whyNot(
            String memoryId, String query, RecallOptions options) {
        return memory.whyNot(memoryId, query, options);
    }
}

