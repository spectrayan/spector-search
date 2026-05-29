package com.spectrayan.spector.node.api.dto;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.spectrayan.spector.query.SearchResponse;

/**
 * Response DTO for the search endpoint ({@code POST /api/v1/search}).
 *
 * @param results     scored search results
 * @param totalHits   total number of matches
 * @param queryTimeMs query execution time in milliseconds
 * @param mode        search mode used (KEYWORD, VECTOR, HYBRID)
 */
public record SearchResponseDto(
        List<Map<String, Object>> results,
        int totalHits,
        long queryTimeMs,
        String mode
) {

    /**
     * Creates a DTO from the engine's search response.
     */
    public static SearchResponseDto from(SearchResponse response) {
        var resultList = Arrays.stream(response.results())
                .map(r -> Map.<String, Object>of(
                        "id", r.id(),
                        "score", r.score()
                ))
                .toList();

        return new SearchResponseDto(
                resultList,
                response.totalHits(),
                response.queryTimeMs(),
                response.mode().name()
        );
    }
}
