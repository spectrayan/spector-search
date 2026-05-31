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
package com.spectrayan.spector.memory.amygdala;

import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;

/**
 * Outcome-driven emotional reinforcement tracker.
 *
 * <h3>Biological Analog: Amygdala Valence Tagging</h3>
 * <p>The amygdala doesn't predict emotions at encoding time — it learns them
 * from outcomes. If a memory led to a good result, dopamine reinforces it
 * with positive valence. If it led to a bad result, cortisol tags it with
 * negative valence. This is why you instinctively avoid things that hurt you.</p>
 *
 * <h3>Design: Outcome-Driven, Not LLM-Guessed</h3>
 * <p>Valence is NOT assigned at ingestion time. It's updated via
 * {@link #reinforce(MemorySegment, long, CognitiveRecordLayout, byte)} after
 * the agent observes whether using a memory led to success or failure.
 * This gives ground-truth reinforcement, not hallucinated importance.</p>
 *
 * <h3>Learning Rate</h3>
 * <p>Uses exponential moving average with α=0.3 by default. New outcomes
 * weigh more than old ones, allowing the agent to "change its mind" about
 * a memory's value over time.</p>
 */
public final class ValenceTracker {

    private static final Logger log = LoggerFactory.getLogger(ValenceTracker.class);

    private final float learningRate;

    /**
     * Creates a valence tracker.
     *
     * @param learningRate exponential moving average alpha (0.0–1.0, default: 0.3)
     */
    public ValenceTracker(float learningRate) {
        this.learningRate = learningRate;
    }

    /**
     * Creates a valence tracker with default learning rate (0.3).
     */
    public ValenceTracker() {
        this(0.3f);
    }

    /**
     * Reinforces a memory with an outcome valence.
     *
     * <p>Blends the new valence into the existing value using exponential moving average.
     * This allows gradual learning — a memory used 10 times with positive outcomes
     * will have strongly positive valence even if one use was negative.</p>
     *
     * @param segment   off-heap segment containing the record
     * @param offset    record offset within the segment
     * @param layout    cognitive record layout
     * @param outcome   outcome valence (use {@link Valence} constants)
     */
    public void reinforce(MemorySegment segment, long offset,
                           CognitiveRecordLayout layout, byte outcome) {
        byte currentValence = layout.readValence(segment, offset);
        byte blended = Valence.blend(currentValence, outcome, learningRate);

        segment.set(SynapticHeaderConstants.LAYOUT_VALENCE,
                offset + SynapticHeaderConstants.OFFSET_VALENCE, blended);

        log.debug("Valence reinforced at offset {}: {} → {} (outcome={})",
                offset, currentValence, blended, outcome);
    }

    /**
     * Returns the learning rate.
     */
    public float learningRate() {
        return learningRate;
    }
}
