/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory;

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.id.MemoryIdGenerator;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.inhibition.SuppressionSet;
import com.spectrayan.spector.memory.metamemory.MemoryInsight;
import com.spectrayan.spector.memory.model.CognitiveProfile;
import com.spectrayan.spector.memory.model.CognitiveRecord;
import com.spectrayan.spector.memory.model.CognitiveResult;
import com.spectrayan.spector.memory.model.ImportanceEstimate;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.model.IngestionContext;
import com.spectrayan.spector.memory.model.RecallOptions;
import com.spectrayan.spector.memory.model.ReflectReport;
import com.spectrayan.spector.memory.model.WhyNotExplanation;
import com.spectrayan.spector.memory.neurodivergent.LateralEvaluator;
import com.spectrayan.spector.memory.pipeline.CognitiveIngestionTarget;
import com.spectrayan.spector.memory.pipeline.RecallPipeline;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.prospective.Reminder;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.temporal.TemporalChain;

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

    /**
     * Ingests a new memory with cognitive hints (ICNU + valence + arousal).
     *
     * <p>The hints allow the caller (typically an LLM) to provide subjective
     * importance signals — Interest, Challenge, Urgency — which are fused with
     * Spector's native Novelty signal to compute final importance. Emotional
     * valence and arousal modulate decay rates and recall ranking.</p>
     *
     * @param id     unique memory identifier
     * @param text   memory content
     * @param type   cognitive tier (WORKING, EPISODIC, SEMANTIC, PROCEDURAL)
     * @param source provenance (USER_STATED, OBSERVED, INFERRED, PROCEDURAL)
     * @param hints  ICNU + emotional context (null for novelty-only importance)
     * @param tags   synaptic tag strings for Bloom filter encoding
     * @see com.spectrayan.spector.memory.neurodivergent.IngestionHints
     */
    CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                      MemorySource source,
                                      com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                      String... tags);

    /**
     * Ingests a new memory with full cognitive context.
     *
     * <p>The {@link IngestionContext} consolidates all LLM-provided metadata:
     * ICNU hints, pre-extracted entities, Hebbian edge hints, and temporal
     * chain links. This enables a single-call ingestion with complete
     * cognitive context — ideal for MCP tool integration.</p>
     *
     * @param id      unique memory identifier
     * @param text    memory content
     * @param type    cognitive tier (WORKING, EPISODIC, SEMANTIC, PROCEDURAL)
     * @param source  provenance (USER_STATED, OBSERVED, INFERRED, PROCEDURAL)
     * @param context consolidated cognitive metadata (entities, edges, links, hints)
     * @param tags    synaptic tag strings for Bloom filter encoding
     * @see IngestionContext
     */
    CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                      MemorySource source,
                                      IngestionContext context,
                                      String... tags);

    /** Convenience overload with default source. */
    CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                      String... tags);

    // ══════════════════════════════════════════════════════════════
    // AUTO-ID INGESTION — ID generated automatically
    // ══════════════════════════════════════════════════════════════

    /**
     * Ingests a memory with an auto-generated ID.
     *
     * <p>The ID is generated using the configured {@link MemoryIdGenerator}
     * (default: TSID — 13-char time-sorted identifier). This is the preferred
     * API for LLM/MCP callers who don't need to manage IDs manually.</p>
     *
     * @param text   the memory content
     * @param type   cognitive tier
     * @param source provenance source
     * @param tags   synaptic tag strings
     * @return a future that completes with the generated ID
     */
    CompletableFuture<String> remember(String text, MemoryType type,
                                       MemorySource source, String... tags);

    /**
     * Ingests a memory with auto-generated ID and cognitive hints.
     *
     * @param text   the memory content
     * @param type   cognitive tier
     * @param source provenance source
     * @param hints  ICNU + emotional context (null for novelty-only)
     * @param tags   synaptic tag strings
     * @return a future that completes with the generated ID
     */
    CompletableFuture<String> remember(String text, MemoryType type,
                                       MemorySource source,
                                       com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                       String... tags);

    /**
     * Ingests a memory with auto-generated ID and full cognitive context.
     *
     * <p>This is the richest auto-ID overload — carries metadata (source modality,
     * asset URIs), ICNU hints, entities, Hebbian edges, and temporal links in
     * a single {@link IngestionContext}. Preferred for multimodal ingestion.</p>
     *
     * @param text    the memory content (or extracted caption/transcript)
     * @param type    cognitive tier
     * @param source  provenance source
     * @param context consolidated cognitive metadata (metadata, hints, entities, etc.)
     * @param tags    synaptic tag strings
     * @return a future that completes with the generated ID
     */
    CompletableFuture<String> remember(String text, MemoryType type,
                                       MemorySource source,
                                       IngestionContext context,
                                       String... tags);

    /**
     * Ingests a file as a memory with auto-generated ID.
     *
     * <p>Convenience method that builds an {@link IngestionContext} with the file path
     * in the {@code attachments} metadata key. The pipeline auto-detects MIME type,
     * routes to the appropriate {@code SensoryExtractor}, and stores extracted
     * content as sub-memories linked to the parent.</p>
     *
     * <p>If {@code text} is null or blank, the extracted caption/transcript
     * becomes the memory text. If {@code text} is provided, it serves as the
     * semantic anchor with the file as an attachment.</p>
     *
     * @param filePath local file path to ingest
     * @param text     optional text description (null = use extracted text)
     * @param type     cognitive tier
     * @param source   provenance source
     * @param tags     synaptic tag strings
     * @return a future that completes with the generated ID
     */
    default CompletableFuture<String> rememberFile(java.nio.file.Path filePath,
                                                    String text,
                                                    MemoryType type,
                                                    MemorySource source,
                                                    String... tags) {
        String effectiveText = (text != null && !text.isBlank()) ? text : filePath.getFileName().toString();
        IngestionContext context = IngestionContext.builder()
                .metadata(com.spectrayan.spector.memory.model.SourceModality.ATTACHMENTS_KEY,
                        filePath.toAbsolutePath().toString())
                .build();
        return remember(effectiveText, type, source, context, tags);
    }

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
    // IMPORTANCE ESTIMATION — pre-ingestion computation
    // ══════════════════════════════════════════════════════════════

    /**
     * Computes importance for a prospective memory <em>without</em> ingesting it.
     *
     * <p>This is a <b>read-only, side-effect-free</b> operation. It embeds the text,
     * computes novelty against the existing store, and fuses with optional ICNU
     * hints to produce a full importance estimate. The LLM can use this to:
     * <ul>
     *   <li>Preview what importance a memory would receive before committing</li>
     *   <li>Adjust ICNU hints (Interest, Challenge, Urgency) based on the novelty signal</li>
     *   <li>Detect near-duplicates via the nearest memory ID</li>
     *   <li>Understand how the active cognitive profile affects scoring</li>
     * </ul>
     *
     * <h3>MCP Workflow</h3>
     * <pre>{@code
     *   // 1. LLM asks for importance estimate
     *   var est = memory.estimateImportance("The database crashed", hints);
     *   // → novelty=0.82, fused=7.8, nearest="mem-42"
     *
     *   // 2. LLM decides to proceed (or skip if near-duplicate)
     *   memory.remember("db-crash", "The database crashed...", ...);
     * }</pre>
     *
     * @param text  the memory text to evaluate
     * @param hints optional ICNU hints (null = novelty-only estimate)
     * @return importance estimate with novelty, fusion, nearest memory, and profile weights
     */
    ImportanceEstimate estimateImportance(String text,
                                          com.spectrayan.spector.memory.neurodivergent.IngestionHints hints);

    /**
     * Convenience overload — estimates importance with novelty-only (no ICNU hints).
     */
    default ImportanceEstimate estimateImportance(String text) {
        return estimateImportance(text, null);
    }

    // ══════════════════════════════════════════════════════════════
    // EXTENDED API — reinforce / suppress / introspect
    // ══════════════════════════════════════════════════════════════

    /** Reports an outcome (positive/negative) for a previously recalled memory. */
    void reinforce(String memoryId, byte valence);

    /**
     * Reinforces a memory with optional ICNU hints for importance re-fusion.
     *
     * <p>When {@code updatedHints} is provided, the memory's importance is re-fused
     * using the updated ICNU weights (Interest, Challenge, Novelty, Urgency).
     * When {@code updatedHints} is null, importance is auto-adjusted based on the
     * memory's graph position (degree centrality in the Hebbian graph).</p>
     *
     * @param memoryId     the memory ID to reinforce
     * @param valence      positive/negative outcome (-128 to +127)
     * @param updatedHints optional ICNU hints for re-fusion (null = auto-compute from graph)
     */
    default void reinforce(String memoryId, byte valence,
                           com.spectrayan.spector.memory.neurodivergent.IngestionHints updatedHints) {
        reinforce(memoryId, valence); // default: delegate to simple reinforce
    }

    /** Suppresses a memory from future recall with a reason. */
    void suppress(String memoryId, String reason);

    /** Suppresses a memory from future recall. */
    void suppress(String memoryId);

    /** Removes a suppression, allowing recall again. */
    void unsuppress(String memoryId);

    /**
     * Marks a memory as resolved (Zeigarnik Effect).
     * Resolved memories return to normal time-decay and gradually fade.
     */
    void markResolved(String memoryId);

    /**
     * Marks a memory as unresolved (Zeigarnik Effect).
     * Unresolved memories resist time-decay and float to the top of recall.
     */
    void markUnresolved(String memoryId);

    /** Introspects the agent's knowledge about a topic (metamemory). */
    MemoryInsight introspect(String topic);

    /**
     * Explains why a specific memory was NOT returned for a given query.
     *
     * <p>Evaluates the memory against the full scoring pipeline (in OBSERVE mode,
     * so no state mutations) and identifies the exact reason it was excluded:
     * not found, tombstoned, suppressed, outranked, or pre-filtered.</p>
     *
     * @param memoryId  the memory ID to investigate
     * @param queryText the query it was expected to match
     * @param options   recall options (profile, filters, etc.) — recallMode is forced to OBSERVE
     * @return a diagnostic explanation of why the memory was not retrieved
     */
    WhyNotExplanation whyNot(String memoryId, String queryText, RecallOptions options);

    // ══════════════════════════════════════════════════════════════
    // INSPECT — Full Cognitive X-Ray
    // ══════════════════════════════════════════════════════════════

    /**
     * Returns the complete cognitive snapshot for a memory — the "X-ray" view.
     *
     * <p>Combines data from three subsystems into a single {@link CognitiveRecord}:</p>
     * <ul>
     *   <li><b>MemoryIndex</b>: text, source, tags, physical location</li>
     *   <li><b>CognitiveHeader</b> (64-byte off-heap): importance, valence, arousal,
     *       recall count, storage strength, synaptic tags, flags</li>
     *   <li><b>Vector payload</b>: quantized INT8 bytes</li>
     * </ul>
     *
     * @param id the memory ID to inspect
     * @return the full cognitive record, or null if the memory is not found
     */
    CognitiveRecord inspect(String id);

    // ══════════════════════════════════════════════════════════════
    // BROWSE — Tag-Based Iteration
    // ══════════════════════════════════════════════════════════════

    /**
     * Returns all memories matching the given tags (without vector search).
     *
     * <p>This is a metadata-only scan — it does not embed a query or compute
     * similarity scores. Useful for browsing, auditing, and bulk operations
     * ("show me all memories tagged 'payments'").</p>
     *
     * <p>If multiple tags are provided, a memory must contain <b>all</b> of them
     * (AND semantics).</p>
     *
     * @param tags one or more tag strings to match
     * @return list of matching cognitive records (without vectors — use inspect() for full detail)
     */
    List<CognitiveRecord> browse(String... tags);

    // ══════════════════════════════════════════════════════════════
    // EXPORT — Bulk Memory Export
    // ══════════════════════════════════════════════════════════════

    /**
     * Exports all memories as a JSON array string.
     *
     * <p>Each memory is serialized as a {@link CognitiveRecord#toJson()} object.
     * This is suitable for backup, migration, audit, and debugging.</p>
     *
     * <p>For large memory stores, consider using {@link #browse(String...)} with
     * tag filters to export subsets.</p>
     *
     * @return JSON array string containing all memory records
     */
    String exportJson();

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

    // ══════════════════════════════════════════════════════════════
    // ADMIN INTERFACE
    // ══════════════════════════════════════════════════════════════

    /**
     * Returns the administrative interface for accessing internal subsystems.
     *
     * <p>Typical SDK consumers should not need this — it provides access to
     * WAL, tier router, Hebbian graph, quantizer, and other internal
     * components for operational monitoring, tuning, and advanced integrations.</p>
     *
     * @return the admin interface (never null)
     * @since 1.0.0
     */
    SpectorMemoryAdmin admin();

    // ══════════════════════════════════════════════════════════════
    // DEPRECATED SUBSYSTEM ACCESSORS (use admin() instead)
    // ══════════════════════════════════════════════════════════════

    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#coActivation() coActivation()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) CoActivationTracker coActivation();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#wal() wal()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) MemoryWal wal();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#prospective() prospective()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) ProspectiveScheduler prospective();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#suppression() suppression()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) SuppressionSet suppression();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#habituation() habituation()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) HabituationPenalty habituation();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#quantizer() quantizer()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) ScalarQuantizer quantizer();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#cognitiveTarget() cognitiveTarget()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) CognitiveIngestionTarget cognitiveTarget();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#recallPipeline() recallPipeline()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) RecallPipeline recallPipeline();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#tierRouter() tierRouter()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) TierRouter tierRouter();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#index() index()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) MemoryIndex index();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#lateralEvaluator() lateralEvaluator()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) LateralEvaluator lateralEvaluator();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#hebbianGraph() hebbianGraph()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) HebbianGraph hebbianGraph();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#temporalChain() temporalChain()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) TemporalChain temporalChain();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#entityGraph() entityGraph()} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) EntityGraph entityGraph();
    /** @deprecated Use {@link #admin()}.{@link SpectorMemoryAdmin#decay(Duration, float) decay(Duration, float)} instead. */
    @Deprecated(since = "1.0.0", forRemoval = true) int decay(Duration olderThan, float factor);

    /** Closes the memory system and persists data. */
    @Override
    void close();
}

