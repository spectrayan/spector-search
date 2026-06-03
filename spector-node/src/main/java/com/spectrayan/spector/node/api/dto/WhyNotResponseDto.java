package com.spectrayan.spector.node.api.dto;

import com.spectrayan.spector.memory.WhyNotExplanation;

/**
 * Response DTO for {@code POST /memory/why-not}.
 *
 * <p>Explains why a specific memory was NOT returned for a given query.</p>
 *
 * @param memoryId   the investigated memory ID
 * @param reason     diagnostic reason (e.g., NOT_FOUND, SUPPRESSED, OUTRANKED)
 * @param exists     whether the memory exists at all
 * @param suppressed whether the memory is currently suppressed
 * @param scoreGap   gap between the memory's score and the topK cutoff
 * @param breakdown  detailed scoring breakdown (null if not applicable)
 * @param summary    human-readable summary text
 */
public record WhyNotResponseDto(
        String memoryId,
        String reason,
        boolean exists,
        boolean suppressed,
        float scoreGap,
        ScoreBreakdownDto breakdown,
        String summary
) {

    /**
     * Creates a response DTO from the domain model.
     */
    public static WhyNotResponseDto from(WhyNotExplanation explanation) {
        return new WhyNotResponseDto(
                explanation.memoryId(),
                explanation.reason().name(),
                explanation.exists(),
                explanation.suppressed(),
                explanation.scoreGap(),
                ScoreBreakdownDto.from(explanation.breakdown()),
                explanation.summary()
        );
    }
}
