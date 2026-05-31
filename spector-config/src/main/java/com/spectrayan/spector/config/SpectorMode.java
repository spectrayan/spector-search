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
package com.spectrayan.spector.config;

/**
 * Global operating mode for a Spector instance.
 *
 * <ul>
 *   <li>{@link #SEARCH} — traditional vector search engine (default)</li>
 *   <li>{@link #MEMORY} — cognitive memory mode with biological mechanisms
 *       (auto-enables memory, routes ingestion/search through memory pipeline)</li>
 * </ul>
 *
 * <p>Set via {@code spector.mode} in configuration (default: {@code search}).</p>
 */
public enum SpectorMode {

    /** Traditional vector search engine. */
    SEARCH,

    /** Cognitive memory mode with decay, consolidation, and importance scoring. */
    MEMORY
}
