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
package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.index.MemoryIndex.MemoryLocation;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.sync.WalEvent;
import com.spectrayan.spector.memory.synapse.ActRActivation;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * LTP Reconsolidation listener — records recall timestamps and WAL events.
 *
 * <h3>Biological Analog: Long-Term Potentiation (LTP)</h3>
 * <p>Each time a memory is successfully recalled, its synaptic strength increases.
 * In Spector's model, this manifests as:</p>
 * <ul>
 *   <li><b>ACT-R recall timestamps</b>: recorded in the 4-slot ring buffer
 *       (V3 layouts only) via {@link ActRActivation#recordRecall}. These
 *       enable the full ACT-R base-level activation computation:
 *       {@code B_i = ln(Σ t_j^{-d})}.</li>
 *   <li><b>Recall count</b>: incremented only on explicit {@code reinforce()}
 *       calls to prevent inflation from passive retrieval.</li>
 * </ul>
 *
 * <h3>Design Pattern: Observer</h3>
 * <p>Previously hardcoded in SpectorMemory.recall() Step 7, now a standalone
 * listener registered with {@link RecallPipeline#addListener}.</p>
 */
public final class LtpReconsolidationListener implements RecallListener {

    /**
     * Minimum interval between auto-LTP reinforcements for the same memory (5 minutes).
     * Prevents runaway LTP from repeated queries hitting the same results.
     */
    private static final long AUTO_LTP_COOLDOWN_MS = 300_000L; // 5 minutes

    private final MemoryIndex index;
    private final TierRouter tierRouter;
    private final MemoryWal wal;

    public LtpReconsolidationListener(MemoryIndex index, TierRouter tierRouter, MemoryWal wal) {
        this.index = index;
        this.tierRouter = tierRouter;
        this.wal = wal;
    }

    @Override
    public void onRecallComplete(List<CognitiveResult> results) {
        long nowMs = System.currentTimeMillis();
        for (CognitiveResult r : results) {
            MemoryLocation loc = index.locate(r.id());
            if (loc != null) {
                MemorySegment segment = tierRouter.segmentFor(loc.type());
                if (segment != null) {
                    CognitiveRecordLayout layout = tierRouter.layoutFor(loc.type());

                    if (layout.headerLayout().version() >= 3) {
                        long creationMs = layout.readTimestamp(segment, loc.offset());

                        // Record recall timestamp for ACT-R base-level activation.
                        // This captures the spacing effect: spaced recalls produce higher
                        // activation than massed recalls, without inflating agent_recall_count.
                        ActRActivation.recordRecall(segment, loc.offset(), creationMs, nowMs);

                        // Auto-LTP: passively reinforce memories that surface in results,
                        // subject to a cooldown to prevent inflation from repeated queries.
                        long lastAutoLtp = layout.readLastAutoLtp(segment, loc.offset());
                        if (nowMs - lastAutoLtp >= AUTO_LTP_COOLDOWN_MS) {
                            // Atomically increment spector-internal recall count
                            layout.incrementSpectorRecallCount(segment, loc.offset());

                            // Update storage strength using Two-Factor formula:
                            // S(t+1) = S(t) + α·(1/R(t)) where R(t) = retrieval strength
                            // Simplified: each auto-LTP adds a small fixed increment (0.05)
                            float currentStrength = layout.readStorageStrength(segment, loc.offset());
                            float newStrength = Math.min(5.0f, currentStrength + 0.05f);
                            layout.writeStorageStrength(segment, loc.offset(), newStrength);

                            // Record cooldown timestamp
                            layout.writeLastAutoLtp(segment, loc.offset(), nowMs);
                        }
                    }
                }

                // Log recall hit for analytics
                wal.append(WalEvent.EventType.RECALL_HIT,
                        index.findIdByOffset(loc.type(), loc.offset()), null);
            }
        }
    }
}
