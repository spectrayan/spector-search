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
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Hebbian co-activation + STDP listener — records both undirected co-occurrence
 * and directed temporal associations from recall results.
 *
 * <h3>Biological Analog: Hebbian Learning + STDP</h3>
 * <p>"Cells that fire together wire together." When multiple memories are recalled
 * together, their synaptic tags form co-activation pairs. Over time, recalling
 * one tag automatically surfaces associated tags — spreading activation.</p>
 *
 * <h3>STDP Extension</h3>
 * <p>Additionally, recall results are treated as a <em>temporal sequence</em>
 * ordered by their cognitive score. The highest-scoring memory is treated as
 * "activated first" (strongest response), and lower-scoring memories as
 * "activated later." This creates directional STDP edges: high-score tags
 * <b>predict</b> lower-score tags.</p>
 *
 * <h3>Design Pattern: Observer</h3>
 * <p>Previously hardcoded in SpectorMemory.recall() Step 8, now a standalone
 * listener registered with {@link RecallPipeline#addListener}.</p>
 */
public final class HebbianCoActivationListener implements RecallListener {

    private final CoActivationTracker tracker;

    public HebbianCoActivationListener(CoActivationTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void onRecallComplete(List<CognitiveResult> results) {
        if (results.size() < 2) return;

        // ── Phase 1: Undirected co-activation (original Hebbian) ──
        String[] resultTags = results.stream()
                .flatMap(r -> r.synapticTags() != null
                        ? java.util.Arrays.stream(r.synapticTags())
                        : java.util.stream.Stream.<String>empty())
                .distinct()
                .limit(10)
                .toArray(String[]::new);

        if (resultTags.length >= 2) {
            tracker.recordCoActivation(resultTags);
        }

        // ── Phase 2: STDP sequential activation (directed) ──
        // Results are already sorted by score (highest first).
        // Treat the result order as temporal activation order:
        //   result[0].tags  →  result[1].tags  →  result[2].tags  ...
        // This creates causal associations: "java" in a high-score result
        // predicts "gc" in a lower-score result.
        long nowMs = System.currentTimeMillis();
        List<String> orderedTags = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        // Create a synthetic temporal sequence from result score ordering
        // Each result is spaced 1 second apart for STDP exponential window
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            CognitiveResult r = results.get(i);
            if (r.synapticTags() == null) continue;

            // Pick the first tag from each result as the representative
            for (String tag : r.synapticTags()) {
                if (!orderedTags.contains(tag)) {
                    orderedTags.add(tag);
                    timestamps.add(nowMs + i * 1000L);  // 1 second spacing
                    if (orderedTags.size() >= 8) break;
                }
            }
            if (orderedTags.size() >= 8) break;
        }

        if (orderedTags.size() >= 2) {
            tracker.recordSequentialActivations(orderedTags, timestamps);
        }
    }
}
