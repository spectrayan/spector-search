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
 * Response model for document ingestion operations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngestResponse {

    private String id;
    private boolean indexed;
    private boolean autoEmbedded;
    private int total;
    private int success;
    private int failed;

    public IngestResponse() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isIndexed() { return indexed; }
    public void setIndexed(boolean indexed) { this.indexed = indexed; }

    public boolean isAutoEmbedded() { return autoEmbedded; }
    public void setAutoEmbedded(boolean autoEmbedded) { this.autoEmbedded = autoEmbedded; }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public int getSuccess() { return success; }
    public void setSuccess(int success) { this.success = success; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
}
