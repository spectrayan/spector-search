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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response model for server metrics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricsResponse {

    private long uptimeMs;
    private long totalRequests;
    private long totalSearches;
    private long totalIngestions;
    private long totalErrors;
    private long documents;
    private boolean gpu;
    private boolean reranker;

    public MetricsResponse() {}

    public long getUptimeMs() { return uptimeMs; }
    public void setUptimeMs(long uptimeMs) { this.uptimeMs = uptimeMs; }

    public long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }

    public long getTotalSearches() { return totalSearches; }
    public void setTotalSearches(long totalSearches) { this.totalSearches = totalSearches; }

    public long getTotalIngestions() { return totalIngestions; }
    public void setTotalIngestions(long totalIngestions) { this.totalIngestions = totalIngestions; }

    public long getTotalErrors() { return totalErrors; }
    public void setTotalErrors(long totalErrors) { this.totalErrors = totalErrors; }

    public long getDocuments() { return documents; }
    public void setDocuments(long documents) { this.documents = documents; }

    public boolean isGpu() { return gpu; }
    public void setGpu(boolean gpu) { this.gpu = gpu; }

    public boolean isReranker() { return reranker; }
    public void setReranker(boolean reranker) { this.reranker = reranker; }
}
