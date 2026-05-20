package com.spectrayan.spector.engine;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.index.ScoredResult;

/**
 * Unit tests for {@link RescoreStrategy}.
 */
class RescoreStrategyTest {

    @Test
    void constructorRejectsZeroOversamplingFactor() {
        assertThatThrownBy(() -> new RescoreStrategy(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oversamplingFactor");
    }

    @Test
    void constructorRejectsNegativeOversamplingFactor() {
        assertThatThrownBy(() -> new RescoreStrategy(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oversamplingFactor");
    }

    @Test
    void constructorAcceptsFactorOfOne() {
        RescoreStrategy strategy = new RescoreStrategy(1);
        assertThat(strategy.oversamplingFactor()).isEqualTo(1);
    }

    @Test
    void candidateCountReturnsFactorTimesK() {
        RescoreStrategy strategy = new RescoreStrategy(3);
        assertThat(strategy.candidateCount(10, 1000)).isEqualTo(30);
    }

    @Test
    void candidateCountCappedByTotalVectors() {
        RescoreStrategy strategy = new RescoreStrategy(5);
        // 5 * 10 = 50, but only 20 vectors available
        assertThat(strategy.candidateCount(10, 20)).isEqualTo(20);
    }

    @Test
    void candidateCountWhenTotalEqualsOversampledCount() {
        RescoreStrategy strategy = new RescoreStrategy(3);
        assertThat(strategy.candidateCount(10, 30)).isEqualTo(30);
    }

    @Test
    void rescoreReturnsTopKByExactDistance() {
        RescoreStrategy strategy = new RescoreStrategy(3);

        // Simulate 6 candidates from quantized search (k=2, factor=3 → 6 candidates)
        List<ScoredResult> quantizedCandidates = List.of(
                new ScoredResult("a", 0, 0.9f),
                new ScoredResult("b", 1, 0.8f),
                new ScoredResult("c", 2, 0.7f),
                new ScoredResult("d", 3, 0.6f),
                new ScoredResult("e", 4, 0.5f),
                new ScoredResult("f", 5, 0.4f)
        );

        // Exact distances differ from quantized scores — "e" and "c" are actually closest
        float[] exactDistances = {0.50f, 0.80f, 0.10f, 0.70f, 0.05f, 0.60f};

        float[] query = {1.0f, 2.0f};
        int k = 2;

        List<ScoredResult> results = strategy.rescore(
                query,
                k,
                n -> quantizedCandidates.subList(0, Math.min(n, quantizedCandidates.size())),
                (q, idx) -> exactDistances[idx]
        );

        assertThat(results).hasSize(2);
        // Best exact distance is "e" (0.05), then "c" (0.10)
        assertThat(results.get(0).id()).isEqualTo("e");
        assertThat(results.get(0).score()).isEqualTo(0.05f);
        assertThat(results.get(1).id()).isEqualTo("c");
        assertThat(results.get(1).score()).isEqualTo(0.10f);
    }

    @Test
    void rescoreWithFewerCandidatesThanK() {
        RescoreStrategy strategy = new RescoreStrategy(3);

        // Only 2 candidates available even though k=5
        List<ScoredResult> quantizedCandidates = List.of(
                new ScoredResult("x", 0, 0.5f),
                new ScoredResult("y", 1, 0.3f)
        );

        float[] query = {1.0f};

        List<ScoredResult> results = strategy.rescore(
                query,
                5,
                n -> quantizedCandidates,
                (q, idx) -> idx == 0 ? 0.2f : 0.1f
        );

        // Should return all available (2), sorted by exact distance
        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("y");
        assertThat(results.get(0).score()).isEqualTo(0.1f);
        assertThat(results.get(1).id()).isEqualTo("x");
        assertThat(results.get(1).score()).isEqualTo(0.2f);
    }

    @Test
    void rescoreRequestsCorrectCandidateCount() {
        RescoreStrategy strategy = new RescoreStrategy(4);

        List<Integer> requestedCounts = new ArrayList<>();

        List<ScoredResult> candidates = List.of(
                new ScoredResult("a", 0, 0.5f)
        );

        float[] query = {1.0f};

        strategy.rescore(
                query,
                3,
                n -> {
                    requestedCounts.add(n);
                    return candidates;
                },
                (q, idx) -> 0.1f
        );

        // Should request factor * k = 4 * 3 = 12 candidates
        assertThat(requestedCounts).containsExactly(12);
    }

    @Test
    void oversamplingFactorAccessor() {
        assertThat(new RescoreStrategy(7).oversamplingFactor()).isEqualTo(7);
    }
}
