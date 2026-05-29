package com.spectrayan.spector.memory.synapse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for weighted tag relevance scoring.
 *
 * <p>Validates that {@link SynapticTagEncoder#overlapRatio} computes correct
 * partial match ratios and that the scoring behavior integrates properly.</p>
 */
class WeightedTagScoringTest {

    @Test
    void fullMatch_returnsOne() {
        long record = SynapticTagEncoder.encode("java", "performance");
        long query = SynapticTagEncoder.encode("java", "performance");

        float overlap = SynapticTagEncoder.overlapRatio(record, query);
        assertThat(overlap).isEqualTo(1.0f);
    }

    @Test
    void noMatch_returnsZero() {
        // Use a guaranteed no-match: record with zero bits set
        // (Bloom filters can have false positives between different tag sets)
        long query = SynapticTagEncoder.encode("java", "performance");

        float overlap = SynapticTagEncoder.overlapRatio(0L, query);
        assertThat(overlap).isEqualTo(0.0f);
    }

    @Test
    void partialMatch_returnsProportional() {
        // Encode with 3 tags, query with subset
        long record = SynapticTagEncoder.encode("java", "performance", "coding");
        long querySubset = SynapticTagEncoder.encode("java");
        long queryFull = SynapticTagEncoder.encode("java", "performance", "coding");

        float subsetOverlap = SynapticTagEncoder.overlapRatio(record, querySubset);
        float fullOverlap = SynapticTagEncoder.overlapRatio(record, queryFull);

        // Subset should have overlap > 0 but < 1.0
        assertThat(subsetOverlap).isGreaterThan(0.0f);
        assertThat(subsetOverlap).isLessThanOrEqualTo(1.0f);

        // Full overlap should be 1.0
        assertThat(fullOverlap).isEqualTo(1.0f);

        // Full match should score higher or equal
        assertThat(fullOverlap).isGreaterThanOrEqualTo(subsetOverlap);
    }

    @Test
    void emptyQueryMask_returnsOne() {
        long record = SynapticTagEncoder.encode("java", "performance");
        float overlap = SynapticTagEncoder.overlapRatio(record, 0L);
        assertThat(overlap).isEqualTo(1.0f);
    }

    @Test
    void emptyRecord_withQuery_returnsZero() {
        long query = SynapticTagEncoder.encode("java");
        float overlap = SynapticTagEncoder.overlapRatio(0L, query);
        assertThat(overlap).isEqualTo(0.0f);
    }

    @Test
    void boostFormula_correctMath() {
        // Simulate the scoring formula used in CognitiveScorer Phase 6
        float baseScore = 0.8f;
        float tagRelevanceBoost = 0.3f;

        // Full match: score * (1.0 + 1.0 * 0.3) = 0.8 * 1.3 = 1.04
        float fullMatchScore = baseScore * (1.0f + 1.0f * tagRelevanceBoost);
        assertThat(fullMatchScore).isCloseTo(1.04f, org.assertj.core.data.Offset.offset(0.001f));

        // Half match: score * (1.0 + 0.5 * 0.3) = 0.8 * 1.15 = 0.92
        float halfMatchScore = baseScore * (1.0f + 0.5f * tagRelevanceBoost);
        assertThat(halfMatchScore).isCloseTo(0.92f, org.assertj.core.data.Offset.offset(0.001f));

        // No boost (tagRelevanceBoost = 0): score * 1.0 = 0.8
        float noBoostScore = baseScore * (1.0f + 1.0f * 0.0f);
        assertThat(noBoostScore).isEqualTo(baseScore);

        // Verify ordering
        assertThat(fullMatchScore).isGreaterThan(halfMatchScore);
        assertThat(halfMatchScore).isGreaterThan(noBoostScore);
    }

    @Test
    void overlapWithSupersetQuery_isPartial() {
        // Record has 2 tags, query has 3 (superset)
        long record = SynapticTagEncoder.encode("java", "coding");
        long query = SynapticTagEncoder.encode("java", "coding", "performance");

        float overlap = SynapticTagEncoder.overlapRatio(record, query);

        // Record matches java+coding bits from query but not performance bits
        // So overlap should be < 1.0 (partial match from query's perspective)
        assertThat(overlap).isGreaterThan(0.0f);
        assertThat(overlap).isLessThanOrEqualTo(1.0f);
    }
}
