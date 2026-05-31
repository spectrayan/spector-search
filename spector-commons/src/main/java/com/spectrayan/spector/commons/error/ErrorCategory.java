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
package com.spectrayan.spector.commons.error;

/**
 * Error categories for the Spector exception framework.
 *
 * <p>Each category owns a range of numeric codes in the {@code SPE-XXX-YYY} schema,
 * where the category prefix ({@code XXX}) identifies the subsystem and the suffix
 * ({@code YYY}) identifies the specific error within that subsystem.</p>
 *
 * <p>Categories are grouped by hundreds for logical affinity:
 * <ul>
 *   <li>{@code 1xx} — Input and configuration</li>
 *   <li>{@code 2xx} — Index and storage</li>
 *   <li>{@code 3xx} — Embedding and cognitive memory</li>
 *   <li>{@code 4xx} — GPU and hardware</li>
 *   <li>{@code 5xx} — Server and client transport</li>
 *   <li>{@code 6xx} — Ingestion pipeline</li>
 *   <li>{@code 7xx} — Cluster and distribution</li>
 *   <li>{@code 8xx} — Reserved for future expansion</li>
 *   <li>{@code 9xx} — Internal / framework</li>
 * </ul>
 *
 * @see ErrorCode
 * @see SpectorException
 */
public enum ErrorCategory {

    /** Input validation failures — bad arguments, null values, range violations. */
    VALIDATION  ("Validation",     100, 109),

    /** Configuration loading, parsing, and value validation errors. */
    CONFIG      ("Configuration",  110, 119),

    /** Index construction, search, persistence, and integrity errors. */
    INDEX       ("Index",          200, 209),

    /** Vector store, memory-mapped I/O, off-heap segment, and disk errors. */
    STORAGE     ("Storage",        210, 219),

    /** Embedding provider communication, model, and timeout errors. */
    EMBEDDING   ("Embedding",      300, 309),

    /** Cognitive memory tier, scoring pipeline, and WAL errors. */
    MEMORY      ("Memory",         310, 319),

    /** CUDA driver, GPU memory allocation, and kernel launch errors. */
    GPU         ("GPU",            400, 409),

    /** REST API, gRPC, and MCP transport errors. */
    SERVER      ("Server",         500, 509),

    /** Client SDK communication and response parsing errors. */
    CLIENT      ("Client",         510, 519),

    /** Document parsing, chunking, and ingestion pipeline errors. */
    INGESTION   ("Ingestion",      600, 609),

    /** Distributed mode — sharding, routing, and membership errors. */
    CLUSTER     ("Cluster",        700, 709),

    /** Internal bugs, invariant violations, and unreachable code paths. */
    INTERNAL    ("Internal",       900, 909);

    private final String displayName;
    private final int rangeStart;
    private final int rangeEnd;

    ErrorCategory(String displayName, int rangeStart, int rangeEnd) {
        this.displayName = displayName;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
    }

    /** Human-readable name of this category, e.g. "Validation". */
    public String displayName() {
        return displayName;
    }

    /** Inclusive lower bound of the category prefix range (e.g. 100). */
    public int rangeStart() {
        return rangeStart;
    }

    /** Inclusive upper bound of the category prefix range (e.g. 109). */
    public int rangeEnd() {
        return rangeEnd;
    }

    /**
     * Returns {@code true} if the given numeric code belongs to this category.
     *
     * @param code the full numeric code (e.g. 100_001)
     * @return true if {@code code / 1000} falls within this category's range
     */
    public boolean contains(int code) {
        int prefix = code / 1000;
        return prefix >= rangeStart && prefix <= rangeEnd;
    }
}
