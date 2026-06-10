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
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.inhibition.SuppressionSet;
import com.spectrayan.spector.memory.neurodivergent.LateralEvaluator;
import com.spectrayan.spector.memory.pipeline.CognitiveIngestionTarget;
import com.spectrayan.spector.memory.pipeline.RecallPipeline;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.sync.CompactionResult;
import com.spectrayan.spector.memory.temporal.TemporalChain;

import java.time.Duration;
import java.util.Map;

/**
 * Administrative interface for the Spector Cognitive Memory system.
 *
 * <p>Provides access to internal subsystems (WAL, tier router, Hebbian graph,
 * entity graph, temporal chain, quantizer, etc.) for operational monitoring,
 * tuning, and advanced integrations.</p>
 *
 * <p>This interface is <b>not intended for typical SDK consumers</b>.
 * Use {@link SpectorMemory} for the public API (remember, recall, forget, etc.).
 * Access this via {@link SpectorMemory#admin()}.</p>
 *
 * @since 1.0.0
 * @see SpectorMemory
 */
public interface SpectorMemoryAdmin {

    // ══════════════════════════════════════════════════════════════
    // INGESTION TARGET
    // ══════════════════════════════════════════════════════════════

    /** Returns the cognitive ingestion target for use with the unified IngestionPipeline. */
    CognitiveIngestionTarget target();

    /** Returns the cognitive ingestion target. */
    CognitiveIngestionTarget cognitiveTarget();

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

    /** Returns the recall pipeline. */
    RecallPipeline recallPipeline();

    /** Returns the tier router (Working, Episodic, Semantic, Procedural). */
    TierRouter tierRouter();

    /** Returns the memory index. */
    MemoryIndex index();

    /** Returns the lateral (neurodivergent) evaluator. */
    LateralEvaluator lateralEvaluator();

    // ══════════════════════════════════════════════════════════════
    // GRAPH SUBSYSTEM ACCESSORS (3-Layer Cognitive Graph)
    // ══════════════════════════════════════════════════════════════

    /** Returns the Hebbian memory-to-memory association graph (nullable if disabled). */
    HebbianGraph hebbianGraph();

    /** Returns the temporal causal chain (nullable if disabled). */
    TemporalChain temporalChain();

    /** Returns the entity-relationship graph (nullable if disabled). */
    EntityGraph entityGraph();

    // ══════════════════════════════════════════════════════════════
    // OPERATIONAL
    // ══════════════════════════════════════════════════════════════

    /** Explicitly decays importance of old episodic memories. */
    int decay(Duration olderThan, float factor);

    // ══════════════════════════════════════════════════════════════
    // VACUUM / COMPACTION
    // ══════════════════════════════════════════════════════════════

    /**
     * Vacuums (compacts) a specific memory tier by removing tombstoned records.
     *
     * <p>Copies only live records to a new segment, updates the index,
     * and reclaims space. The operation is synchronized with writers.</p>
     *
     * @param tier the memory tier to compact
     * @return compaction result with statistics, or null if no compaction needed
     */
    CompactionResult vacuum(MemoryType tier);

    /**
     * Returns the tombstone ratio for each memory tier.
     *
     * @return map of tier → tombstone ratio (0.0 to 1.0)
     */
    Map<MemoryType, Float> tombstoneRatios();
}
