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
    void fuse_allMax_producesMaxImportance() {
        var w = IcnuWeights.DEFAULT;
        float importance = w.fuse(1.0f, 1.0f, 1.0f, 1.0f);
        assertThat(importance).isCloseTo(10.0f, offset(0.01f));
    }

    @Test
    void fuse_allZero_producesMinImportance() {
        var w = IcnuWeights.DEFAULT;
        float importance = w.fuse(0f, 0f, 0f, 0f);
        assertThat(importance).isCloseTo(0.05f, offset(0.01f));
    }

    @Test
    void fuse_noveltyOnlyMode_ignoresHints() {
        var w = IcnuWeights.NOVELTY_ONLY;
        float importance = w.fuse(1.0f, 1.0f, 0.5f, 1.0f);
        // Only novelty matters — noveltyNorm=0.5 → scaled value
        float expected = 0.05f + 0.5f * (10.0f - 0.05f);
        assertThat(importance).isCloseTo(expected, offset(0.01f));
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
