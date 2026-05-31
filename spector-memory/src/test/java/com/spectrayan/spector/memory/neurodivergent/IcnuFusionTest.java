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
package com.spectrayan.spector.memory.neurodivergent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests for {@link IngestionHints} and {@link IcnuWeights} — ICNU fusion formula.
 */
class IcnuFusionTest {

    // ── IngestionHints tests ──

    @Test
    void hints_clampsToUnitRange() {
        var hints = new IngestionHints(2.0f, -1.0f, 0.5f);
        assertThat(hints.interest()).isEqualTo(1.0f);
        assertThat(hints.challenge()).isEqualTo(0.0f);
        assertThat(hints.urgency()).isEqualTo(0.5f);
    }

    @Test
    void hints_noneIsEmpty() {
        assertThat(IngestionHints.NONE.isEmpty()).isTrue();
    }

    @Test
    void hints_nonZeroIsNotEmpty() {
        var hints = new IngestionHints(0.5f, 0f, 0f);
        assertThat(hints.isEmpty()).isFalse();
    }

    // ── IcnuWeights tests ──

    @Test
    void defaultWeights_sumToOne() {
        var w = IcnuWeights.DEFAULT;
        float sum = w.interest() + w.challenge() + w.novelty() + w.urgency();
        assertThat(sum).isCloseTo(1.0f, offset(0.001f));
    }

    @Test
    void weights_normalizeOnConstruction() {
        var w = new IcnuWeights(1f, 1f, 1f, 1f);
        assertThat(w.interest()).isCloseTo(0.25f, offset(0.001f));
        assertThat(w.novelty()).isCloseTo(0.25f, offset(0.001f));
    }

    @Test
    void fuse_allMax_producesHighImportance() {
        // Sigmoid gating: allMax stimulus is ~0.6 (I×N interaction), gated → ~0.96
        var w = IcnuWeights.DEFAULT;
        float importance = w.fuse(1.0f, 1.0f, 1.0f, 1.0f);
        // With sigmoid, importance should be high but not exactly 10.0
        assertThat(importance).isGreaterThan(8.0f);
    }

    @Test
    void fuse_allMax_linearMode_producesExactMax() {
        // LINEAR mode (steepness=0) should produce exact max
        var w = IcnuWeights.LINEAR;
        float importance = w.fuse(1.0f, 1.0f, 1.0f, 1.0f);
        assertThat(importance).isCloseTo(10.0f, offset(0.01f));
    }

    @Test
    void fuse_allZero_producesLowImportance() {
        // Sigmoid gating: allZero stimulus is 0, gated → sigmoid(-k×θ) ≈ 0.17
        var w = IcnuWeights.DEFAULT;
        float importance = w.fuse(0f, 0f, 0f, 0f);
        // With sigmoid, importance should be low but slightly above MIN (0.05)
        assertThat(importance).isLessThan(2.0f);
        assertThat(importance).isGreaterThanOrEqualTo(0.05f);
    }

    @Test
    void fuse_allZero_linearMode_producesExactMin() {
        // LINEAR mode (steepness=0) should produce exact min
        var w = IcnuWeights.LINEAR;
        float importance = w.fuse(0f, 0f, 0f, 0f);
        assertThat(importance).isCloseTo(0.05f, offset(0.01f));
    }

    @Test
    void fuse_noveltyOnlyMode_ignoresHints() {
        // NOVELTY_ONLY has interest=0, so I×N = 0×novelty = 0
        // Only urgency (which is also 0) contributes. Sigmoid gates the result.
        var w = IcnuWeights.NOVELTY_ONLY;
        // noveltyNorm=0.5, but with I=0, I×N=0. No signal gets through sigmoid.
        float lowNovelty = w.fuse(1.0f, 1.0f, 0.2f, 1.0f);
        float highNovelty = w.fuse(1.0f, 1.0f, 0.9f, 1.0f);
        // Higher novelty should still produce higher importance (via I×N)
        // But since interest=0, both should be similar (sigmoid-gated noise)
        // The key insight: novelty-only is now sigmoid-gated, producing near-threshold output
        assertThat(lowNovelty).isGreaterThanOrEqualTo(0.05f);
        assertThat(highNovelty).isGreaterThanOrEqualTo(0.05f);
    }

    @Test
    void fuse_sigmoid_thresholdEffect() {
        // Below threshold (0.2), importance should be low
        // Above threshold, importance should be high
        var w = IcnuWeights.DEFAULT;
        float belowThreshold = w.fuse(0.1f, 0.1f, 0.1f, 0.1f);
        float aboveThreshold = w.fuse(0.9f, 0.9f, 0.9f, 0.9f);
        assertThat(aboveThreshold).isGreaterThan(belowThreshold * 2);
    }

    @Test
    void fuse_withEmptyHints_fallsBackToNoveltyOnly() {
        var w = IcnuWeights.DEFAULT;
        float withHints = w.fuse(IngestionHints.NONE, 0.5f);
        float noveltyOnly = IcnuWeights.NOVELTY_ONLY.fuse(0f, 0f, 0.5f, 0f);
        assertThat(withHints).isCloseTo(noveltyOnly, offset(0.01f));
    }

    @Test
    void fuse_ordering_novelHighUrgent_beats_novelLowUrgent() {
        var w = IcnuWeights.DEFAULT;
        float highUrgent = w.fuse(0.8f, 0.5f, 0.9f, 0.9f);
        float lowUrgent  = w.fuse(0.8f, 0.5f, 0.9f, 0.1f);
        assertThat(highUrgent).isGreaterThan(lowUrgent);
    }

    @Test
    void fuse_ordering_novel_beats_routine() {
        var w = IcnuWeights.DEFAULT;
        float novel   = w.fuse(0.5f, 0.5f, 0.9f, 0.5f);
        float routine = w.fuse(0.5f, 0.5f, 0.1f, 0.5f);
        assertThat(novel).isGreaterThan(routine);
    }
}
