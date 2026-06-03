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

/**
 * Explains why a specific memory was NOT returned in a recall query.
 *
 * <h3>The "Why Not?" Problem</h3>
 * <p>When a developer expects a specific memory to be retrieved but it isn't,
 * the natural question is "why not?" This record provides a complete diagnostic
 * answer by evaluating the memory against the full scoring pipeline and
 * identifying the specific reason it was excluded or outranked.</p>
 *
 * <h3>Elimination Reasons</h3>
 * <ul>
 *   <li>{@link Reason#NOT_FOUND} — the memory ID doesn't exist</li>
 *   <li>{@link Reason#TOMBSTONED} — the memory was deleted (tombstone flag)</li>
 *   <li>{@link Reason#SUPPRESSED} — the memory is in the suppression set</li>
 *   <li>{@link Reason#OUTRANKED} — the memory scored, but didn't make the top-K cutoff</li>
 *   <li>{@link Reason#FILTERED} — eliminated by a pre-filter (tag gate, valence, importance)</li>
 * </ul>
 *
 * @param memoryId   the memory that was investigated
 * @param query      the query it was expected to match
 * @param exists     whether the memory exists in the index
 * @param suppressed whether the memory is in the suppression set
 * @param breakdown  full score breakdown (null if memory doesn't exist or was filtered)
 * @param scoreGap   how far below the top-K cutoff score (0.0 if not outranked)
 * @param reason     the primary reason the memory was not returned
 * @param summary    human-readable diagnostic summary
 */
public record WhyNotExplanation(
        String memoryId,
        String query,
        boolean exists,
        boolean suppressed,
        ScoreBreakdown breakdown,
        float scoreGap,
        Reason reason,
        String summary
) {

    /**
     * Why a memory was not returned in a recall result.
     */
    public enum Reason {
        /** Memory ID was not found in the index. */
        NOT_FOUND,
        /** Memory was logically deleted (tombstone flag set). */
        TOMBSTONED,
        /** Memory was in the suppression set. */
        SUPPRESSED,
        /** Memory scored but was outranked by other results (below top-K cutoff). */
        OUTRANKED,
        /** Memory was eliminated by a pre-filter (tag gate, valence range, importance floor). */
        FILTERED
    }
}
