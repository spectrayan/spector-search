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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-result step-by-step scoring trace from the recall pipeline.
 *
 * <p>Captures how a result's score evolved through each phase of the
 * cognitive recall pipeline: vector similarity, habituation, STDP,
 * Hebbian spreading, temporal chain, entity graph, and top-K cutoff.</p>
 *
 * <h3>Purpose</h3>
 * <p>Turns the cognitive scoring pipeline from a <b>black box</b> into a
 * <b>glass box</b>. When an LLM receives recall results via MCP, it can
 * inspect each result's trace to understand exactly why it ranked where
 * it did — and dynamically adjust query parameters to improve results.</p>
 *
 * <h3>Example Trace Output</h3>
 * <pre>
 * Pipeline Trace for mem-042:
 *   [COGNITIVE_SCORE ] 0.0000 → 0.7440 (47 candidates) α=0.6, sim=0.82, β=0.4, imp=0.9, decay=0.7
 *   [HABITUATION     ] 0.7440 → 0.6324 (47 candidates) habPenalty=0.85, iorPenalty=1.0 → combined=0.85
 *   [HEBBIAN_BOOST   ] 0.6324 → 0.6324 (53 candidates) not a graph-added result
 *   [TOPK_CUTOFF     ] 0.6324 → 0.6324 (10 candidates) rank=4/10, included=true
 * </pre>
 *
 * @param memoryId the memory this trace belongs to
 * @param steps    ordered list of scoring phases with before/after scores
 */
public record RecallTrace(
        String memoryId,
        List<TraceStep> steps
) {

    /**
     * A single phase in the scoring pipeline.
     *
     * @param phaseName        phase identifier (e.g., "COGNITIVE_SCORE", "HABITUATION")
     * @param scoreBefore      score entering this phase
     * @param scoreAfter       score leaving this phase
     * @param candidatesBefore number of candidate results entering this phase
     * @param candidatesAfter  number of candidate results leaving this phase
     * @param detail           human-readable explanation of what happened
     */
    public record TraceStep(
            String phaseName,
            float scoreBefore,
            float scoreAfter,
            int candidatesBefore,
            int candidatesAfter,
            String detail
    ) {
        /** Returns the score delta for this step. */
        public float scoreDelta() {
            return scoreAfter - scoreBefore;
        }

        /** Returns the candidate count delta (positive = candidates added, negative = filtered). */
        public int candidateDelta() {
            return candidatesAfter - candidatesBefore;
        }
    }

    /**
     * Returns a human-readable formatted trace string.
     *
     * <p>Each step is formatted as a fixed-width line showing the phase name,
     * score transition, candidate count, and detail string.</p>
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("Pipeline Trace for ").append(memoryId).append(":\n");
        for (TraceStep step : steps) {
            sb.append(String.format("  [%-16s] %.4f → %.4f (%d→%d candidates) %s%n",
                    step.phaseName(),
                    step.scoreBefore(), step.scoreAfter(),
                    step.candidatesBefore(), step.candidatesAfter(),
                    step.detail() != null ? step.detail() : ""));
        }
        return sb.toString();
    }

    /**
     * Returns the final score after all pipeline phases.
     */
    public float finalScore() {
        if (steps.isEmpty()) return 0f;
        return steps.getLast().scoreAfter();
    }

    /**
     * Returns the phase that caused the largest score change (positive or negative).
     */
    public TraceStep mostImpactfulStep() {
        TraceStep most = null;
        float maxDelta = 0f;
        for (TraceStep step : steps) {
            float absDelta = Math.abs(step.scoreDelta());
            if (absDelta > maxDelta) {
                maxDelta = absDelta;
                most = step;
            }
        }
        return most;
    }

    /**
     * Mutable builder for constructing a RecallTrace during pipeline execution.
     */
    public static final class Builder {
        private final String memoryId;
        private final List<TraceStep> steps = new ArrayList<>();

        public Builder(String memoryId) {
            this.memoryId = memoryId;
        }

        public Builder addStep(String phaseName, float scoreBefore, float scoreAfter,
                               int candidatesBefore, int candidatesAfter, String detail) {
            steps.add(new TraceStep(phaseName, scoreBefore, scoreAfter,
                    candidatesBefore, candidatesAfter, detail));
            return this;
        }

        public RecallTrace build() {
            return new RecallTrace(memoryId, Collections.unmodifiableList(new ArrayList<>(steps)));
        }
    }
}
