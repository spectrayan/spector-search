package com.spectrayan.spector.query;

import com.spectrayan.spector.index.ScoredResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of a search operation.
 *
 * @param results    the scored results, sorted best-first
 * @param totalHits  total number of matching documents (before top-K)
 * @param queryTimeMs time taken to execute the query in milliseconds
 * @param mode       the search mode that was used
 */
public record SearchResponse(
        ScoredResult[] results,
        int totalHits,
        long queryTimeMs,
        SearchQuery.SearchMode mode
) {
    /** Empty response. */
    public static final SearchResponse EMPTY =
            new SearchResponse(new ScoredResult[0], 0, 0, SearchQuery.SearchMode.HYBRID);

    /** Number of results returned. */
    public int size() {
        return results.length;
    }
}
