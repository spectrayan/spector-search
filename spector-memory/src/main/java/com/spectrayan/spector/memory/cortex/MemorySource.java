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
package com.spectrayan.spector.memory.cortex;

/**
 * Provenance tracking for memory source monitoring.
 *
 * <h3>Biological Analog: Source Monitoring / Reality Monitoring</h3>
 * <p>Knowing <em>where</em> a memory came from — did I see it, hear it, read it, or
 * imagine it? Failure of source monitoring causes confabulation ("false memories") —
 * you genuinely remember something that never happened.</p>
 *
 * <p>Each source carries a confidence weight that influences how much the LLM
 * should trust the memory during recall.</p>
 */
public enum MemorySource {

    /**
     * Agent directly processed this content (observed during a task).
     */
    OBSERVED(0.9f),

    /**
     * Explicitly stated by the user (highest trust — ground truth).
     */
    USER_STATED(1.0f),

    /**
     * Synthesized by the ReflectDaemon from an episodic cluster.
     * Lower confidence because it's a computed summary, not raw observation.
     */
    REFLECTED(0.7f),

    /**
     * Agent's own reasoning or conclusion (inference, not observation).
     */
    INFERRED(0.5f),

    /**
     * System prompt, tool template, or procedural rule.
     * High trust because it's system-defined.
     */
    PROCEDURAL(1.0f),

    /**
     * Replayed from another agent's WAL (cross-agent memory sharing).
     * Lower confidence because it's secondhand information.
     */
    TRANSFERRED(0.6f);

    private final float confidenceWeight;

    MemorySource(float confidenceWeight) {
        this.confidenceWeight = confidenceWeight;
    }

    /**
     * Returns the default confidence weight for this source type.
     *
     * @return confidence weight (0.0–1.0)
     */
    public float confidenceWeight() {
        return confidenceWeight;
    }
}
