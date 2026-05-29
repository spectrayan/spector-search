package com.spectrayan.spector.memory;

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Primary interface for the Spector Cognitive Memory system.
 *
 * <p>Provides the full API surface for a Zero-GC cognitive backbone:
 * remember, recall, forget, reinforce, reflect, suppress, introspect,
 * prospective scheduling, working memory scratchpad, and subsystem access.</p>
 *
 * <p>Implementations include {@link DefaultSpectorMemory} (the standard
 * implementation) and metered decorators for observability.</p>
 *
 * <h3>Core API</h3>
 * <ul>
 *   <li>{@link #remember} — Ingest a memory (async, Virtual Thread)</li>
 *   <li>{@link #recall} — Fused cognitive scoring across tiers</li>
 *   <li>{@link #forget} — Tombstone a memory</li>
 *   <li>{@link #reflect} — Trigger sleep consolidation</li>
 *   <li>{@link #reinforce} — Outcome-driven valence update</li>
 *   <li>{@link #suppress} — Session-level recall suppression</li>
 *   <li>{@link #introspect} — Metamemory self-analysis</li>
 *   <li>{@link #scheduleReminder} — Prospective memory</li>
 *   <li>{@link #scratchpad} — Working memory shorthand</li>
 * </ul>
 *
 * @see DefaultSpectorMemory
 */
public interface SpectorMemory extends AutoCloseable {

    // ══════════════════════════════════════════════════════════════
    // INGESTION TARGET
    // ══════════════════════════════════════════════════════════════

    /** Returns the cognitive ingestion target for use with the unified IngestionPipeline. */
    CognitiveIngestionTarget target();

    // ══════════════════════════════════════════════════════════════
    // CORE API — remember / recall / forget / reflect
    // ══════════════════════════════════════════════════════════════

    /** Ingests a new memory asynchronously on a Virtual Thread. */
    CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                      MemorySource source, String... tags);

    /** Convenience overload with default source. */
    CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                      String... tags);

    /** Performs fused cognitive scoring across all relevant memory tiers. */
    List<CognitiveResult> recall(String queryText, RecallOptions options);

    /** Convenience recall using a CognitiveProfile preset. */
    List<CognitiveResult> recall(String queryText, CognitiveProfile profile);

    /** Convenience overload with default options. */
    List<CognitiveResult> recall(String queryText);

    /** Tombstones a memory by ID (logical deletion). */
    void forget(String id);

    /** Triggers a synchronous reflection (sleep consolidation) cycle. */
    ReflectReport reflect();

    // ══════════════════════════════════════════════════════════════
    // EXTENDED API — reinforce / suppress / introspect
    // ══════════════════════════════════════════════════════════════

    /** Reports an outcome (positive/negative) for a previously recalled memory. */
    void reinforce(String memoryId, byte valence);

    /** Suppresses a memory from future recall with a reason. */
    void suppress(String memoryId, String reason);

    /** Suppresses a memory from future recall. */
    void suppress(String memoryId);

    /** Removes a suppression, allowing recall again. */
    void unsuppress(String memoryId);

    /** Introspects the agent's knowledge about a topic (metamemory). */
    MemoryInsight introspect(String topic);

    // ══════════════════════════════════════════════════════════════
    // PROSPECTIVE / SCRATCHPAD / STATS
    // ══════════════════════════════════════════════════════════════

    /** Schedules a reminder at a specific instant. */
    Reminder scheduleReminder(String text, Instant triggerAt, String... tags);

    /** Schedules a reminder after a delay. */
    Reminder scheduleReminder(String text, Duration delay, String... tags);

    /** Stores ephemeral text in working memory. */
    CompletableFuture<Void> scratchpad(String text);

    /** Returns the total number of memories across all tiers. */
    int totalMemories();

    /** Returns the number of memories in a specific tier. */
    int memoryCount(MemoryType type);

    /** Explicitly decays importance of old episodic memories. */
    int decay(Duration olderThan, float factor);

    // ══════════════════════════════════════════════════════════════
    // SUBSYSTEM ACCESSORS
    // ══════════════════════════════════════════════════════════════

    /** Returns the Hebbian co-activation tracker. */
    CoActivationTracker coActivation();

    /** Returns the Write-Ahead Log. */
    MemoryWal wal();

    /** Returns the prospective memory scheduler. */
    ProspectiveScheduler prospective();

    /** Returns the suppression set. */
    SuppressionSet suppression();

    /** Returns the habituation penalty tracker. */
    HabituationPenalty habituation();

    /** Returns the scalar quantizer used for vector compression. */
    ScalarQuantizer quantizer();

    /** Returns the cognitive ingestion target. */
    CognitiveIngestionTarget cognitiveTarget();

    /** Returns the recall pipeline. */
    RecallPipeline recallPipeline();

    /** Returns the tier router (Working, Episodic, Semantic, Procedural). */
    TierRouter tierRouter();

    /** Returns the memory index. */
    MemoryIndex index();

    /** Returns the lateral (neurodivergent) evaluator. */
    LateralEvaluator lateralEvaluator();

    /** Closes the memory system and persists data. */
    @Override
    void close();
}
