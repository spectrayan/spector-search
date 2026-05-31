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
package com.spectrayan.spector.memory.metamemory;

/**
 * Immutable result of a memory introspection query.
 *
 * <p>Contains aggregated statistics about the agent's knowledge on a topic,
 * including confidence, gaps, staleness, and recall frequency.</p>
 *
 * @param query            the introspection query
 * @param totalMemories    number of memories matching the query
 * @param avgImportance    average importance across matching memories
 * @param avgValence       average valence (positive = good outcomes)
 * @param avgAgeDays       average age in days
 * @param confidence       confidence score (0.0–1.0, based on memory count + reinforcement)
 * @param gaps             related topics with zero memories (knowledge gaps)
 * @param staleness        staleness score (0.0–1.0, based on average age vs. freshness)
 * @param recallsPerDay    average recall frequency per day
 * @param recommendation   human-readable recommendation for the agent
 */
public record MemoryInsight(
        String query,
        int totalMemories,
        float avgImportance,
        float avgValence,
        float avgAgeDays,
        float confidence,
        String[] gaps,
        float staleness,
        float recallsPerDay,
        String recommendation
) {

    /**
     * Returns true if the agent has meaningful knowledge about this topic.
     */
    public boolean isKnown() {
        return totalMemories > 0 && confidence > 0.3f;
    }

    /**
     * Returns true if the knowledge is stale and may need refreshing.
     */
    public boolean isStale() {
        return staleness > 0.7f;
    }

    /**
     * Returns true if there are significant knowledge gaps.
     */
    public boolean hasGaps() {
        return gaps != null && gaps.length > 0;
    }

    /**
     * Empty insight — no knowledge found.
     */
    public static MemoryInsight empty(String query) {
        return new MemoryInsight(query, 0, 0f, 0f, 0f, 0f,
                new String[0], 1.0f, 0f,
                "No memories found for '" + query + "'. Consider asking the user.");
    }
}
