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
 * Response model for delete operations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeleteResponse {

    private String id;
    private boolean deleted;

    public DeleteResponse() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
}
