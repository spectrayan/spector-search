package com.spectrayan.spector.runtime;

import com.spectrayan.spector.config.SpectorMode;
import com.spectrayan.spector.memory.MemoryType;

/**
 * Product-level result type for mode-aware queries.
 *
 * <p>Provides a unified view across both search mode and memory mode results.
 * In search mode, only search-specific fields are populated. In memory mode,
 * additional cognitive metadata (importance, age, valence) is included.</p>
 *
 * @param id              document or memory ID
 * @param text            text content
 * @param score           composite relevance score (0.0–1.0)
 * @param rawSimilarity   raw vector similarity (search-mode only, null in memory mode)
 * @param importance      memory importance score (memory-mode only, null in search mode)
 * @param ageDays         age of the memory in days (memory-mode only, null in search mode)
 * @param valence         emotional valence (-128 to 127, memory-mode only, null in search mode)
 * @param mode            which mode produced this result
 * @param tags            associated tags (memory mode, empty array in search mode)
 * @param memoryType      memory tier (memory-mode only, null in search mode)
 */
public record SpectorResult(
        String id,
        String text,
        float score,
        Float rawSimilarity,
        Float importance,
        Float ageDays,
        Byte valence,
        SpectorMode mode,
        String[] tags,
        MemoryType memoryType
) {

    /** Creates a search-mode result. */
    public static SpectorResult fromSearch(String id, String text, float score, float similarity) {
        return new SpectorResult(id, text, score, similarity, null, null, null,
                SpectorMode.SEARCH, new String[0], null);
    }

    /** Creates a memory-mode result. */
    public static SpectorResult fromMemory(String id, String text, float score,
                                            float importance, float ageDays,
                                            byte valence, String[] tags, MemoryType memoryType) {
        return new SpectorResult(id, text, score, null, importance, ageDays, valence,
                SpectorMode.MEMORY, tags, memoryType);
    }
}
