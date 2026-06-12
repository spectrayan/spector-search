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

import com.spectrayan.spector.memory.model.TextSearchMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TextSearchMode} — enum controlling retrieval layer activation.
 *
 * <p>Verifies that each mode correctly reports which retrieval layers it uses
 * via the convenience query methods.</p>
 */
class TextSearchModeTest {

    @Test
    @DisplayName("HYBRID — uses BM25 + Vector, not SPLADE, not ColBERT")
    void hybrid_usesBM25_andVector() {
        assertThat(TextSearchMode.HYBRID.usesBM25()).isTrue();
        assertThat(TextSearchMode.HYBRID.usesVector()).isTrue();
        assertThat(TextSearchMode.HYBRID.usesSPLADE()).isFalse();
        assertThat(TextSearchMode.HYBRID.usesColBERT()).isFalse();
    }

    @Test
    @DisplayName("KEYWORD_ONLY — uses BM25 only")
    void keywordOnly_onlyBM25() {
        assertThat(TextSearchMode.KEYWORD_ONLY.usesBM25()).isTrue();
        assertThat(TextSearchMode.KEYWORD_ONLY.usesVector()).isFalse();
        assertThat(TextSearchMode.KEYWORD_ONLY.usesSPLADE()).isFalse();
        assertThat(TextSearchMode.KEYWORD_ONLY.usesColBERT()).isFalse();
    }

    @Test
    @DisplayName("VECTOR_ONLY — uses Vector only")
    void vectorOnly_onlyVector() {
        assertThat(TextSearchMode.VECTOR_ONLY.usesBM25()).isFalse();
        assertThat(TextSearchMode.VECTOR_ONLY.usesVector()).isTrue();
        assertThat(TextSearchMode.VECTOR_ONLY.usesSPLADE()).isFalse();
        assertThat(TextSearchMode.VECTOR_ONLY.usesColBERT()).isFalse();
    }

    @Test
    @DisplayName("SPLADE — uses SPLADE only, not BM25 or Vector")
    void splade_onlySplade() {
        assertThat(TextSearchMode.SPLADE.usesBM25()).isFalse();
        assertThat(TextSearchMode.SPLADE.usesVector()).isFalse();
        assertThat(TextSearchMode.SPLADE.usesSPLADE()).isTrue();
        assertThat(TextSearchMode.SPLADE.usesColBERT()).isFalse();
    }

    @Test
    @DisplayName("SPLADE_HYBRID — uses SPLADE + Vector, not BM25")
    void spladeHybrid_spladeAndVector() {
        assertThat(TextSearchMode.SPLADE_HYBRID.usesBM25()).isFalse();
        assertThat(TextSearchMode.SPLADE_HYBRID.usesVector()).isTrue();
        assertThat(TextSearchMode.SPLADE_HYBRID.usesSPLADE()).isTrue();
        assertThat(TextSearchMode.SPLADE_HYBRID.usesColBERT()).isFalse();
    }

    @Test
    @DisplayName("LI_LSR — uses SPLADE infrastructure, not BM25 or Vector")
    void liLsr_spladePath() {
        assertThat(TextSearchMode.LI_LSR.usesBM25()).isFalse();
        assertThat(TextSearchMode.LI_LSR.usesVector()).isFalse();
        assertThat(TextSearchMode.LI_LSR.usesSPLADE()).isTrue();
        assertThat(TextSearchMode.LI_LSR.usesColBERT()).isFalse();
    }

    @Test
    @DisplayName("COLBERT_RERANK — uses BM25 + Vector + ColBERT, not SPLADE")
    void colbertRerank_usesColBERT() {
        assertThat(TextSearchMode.COLBERT_RERANK.usesBM25()).isTrue();
        assertThat(TextSearchMode.COLBERT_RERANK.usesVector()).isTrue();
        assertThat(TextSearchMode.COLBERT_RERANK.usesSPLADE()).isFalse();
        assertThat(TextSearchMode.COLBERT_RERANK.usesColBERT()).isTrue();
    }

    @Test
    @DisplayName("FULL_STACK — all four layers active")
    void fullStack_usesAll() {
        assertThat(TextSearchMode.FULL_STACK.usesBM25()).isTrue();
        assertThat(TextSearchMode.FULL_STACK.usesVector()).isTrue();
        assertThat(TextSearchMode.FULL_STACK.usesSPLADE()).isTrue();
        assertThat(TextSearchMode.FULL_STACK.usesColBERT()).isTrue();
    }
}
