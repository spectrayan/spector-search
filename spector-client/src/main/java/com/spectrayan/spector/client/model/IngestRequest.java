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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request model for single document ingestion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestRequest {

    private String id;
    private String title;
    private String content;
    private float[] vector;

    public IngestRequest() {}

    public IngestRequest(String id, String content, float[] vector) {
        this.id = id;
        this.content = content;
        this.vector = vector;
    }

    public IngestRequest(String id, String title, String content, float[] vector) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.vector = vector;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public float[] getVector() { return vector; }
    public void setVector(float[] vector) { this.vector = vector; }
}
