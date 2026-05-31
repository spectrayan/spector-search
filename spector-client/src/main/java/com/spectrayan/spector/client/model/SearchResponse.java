/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.client.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response model for search operations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchResponse {

    private List<SearchResult> results;
    private int totalHits;
    private long queryTimeMs;
    private String mode;

    public SearchResponse() {}

    public List<SearchResult> getResults() { return results; }
    public void setResults(List<SearchResult> results) { this.results = results; }

    public int getTotalHits() { return totalHits; }
    public void setTotalHits(int totalHits) { this.totalHits = totalHits; }

    public long getQueryTimeMs() { return queryTimeMs; }
    public void setQueryTimeMs(long queryTimeMs) { this.queryTimeMs = queryTimeMs; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    /**
     * A single search result entry.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchResult {
        private String id;
        private float score;

        public SearchResult() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }
    }
}
