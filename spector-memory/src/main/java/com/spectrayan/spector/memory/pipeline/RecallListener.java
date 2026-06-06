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

import java.util.List;

/**
 * Observer interface for post-recall hooks.
 *
 * <h3>Design Pattern: Observer</h3>
 * <p>Instead of hardcoding post-recall behavior (LTP reconsolidation, Hebbian
 * co-activation recording, analytics, etc.) directly in the recall pipeline,
 * these are implemented as listeners. This is OCP-compliant — new post-recall
 * behaviors can be added without modifying the pipeline.</p>
 *
 * <h3>Built-in Listeners</h3>
 * <ul>
 *   <li>{@link LtpReconsolidationListener} — increments agent_recall_count for returned memories</li>
 *   <li>{@link HebbianCoActivationListener} — records tag co-occurrence in the
 *       {@link com.spectrayan.spector.memory.hebbian.CoActivationTracker}</li>
 * </ul>
 */
@FunctionalInterface
public interface RecallListener {

    /**
     * Called after each successful recall with the final ranked results.
     *
     * @param results the final recall results (post-filtering, post-habituation)
     */
    void onRecallComplete(List<CognitiveResult> results);
}
