/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.pipeline;

/**
 * Pluggable strategy for extracting synaptic tags from document content.
 *
 * <h3>Biological Analog: Synaptic Tagging</h3>
 * <p>In the Synaptic Tagging and Capture (STC) hypothesis, synapses are
 * "tagged" during learning to mark them for later consolidation. The
 * {@code TagExtractor} determines which contextual markers are assigned
 * to each memory, enabling the 64-bit Bloom filter pre-filtering that
 * eliminates ~95% of irrelevant memories in 1 CPU cycle each.</p>
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@link ContentTagExtractor} — default: extracts tags from document
 *       path segments and significant content words</li>
 *   <li>{@code LlmTagExtractor} (spector-embed-ollama) — uses LLM to
 *       extract semantic tags via prompt</li>
 * </ul>
 *
 * @see CognitiveIngestionTarget
 * @see com.spectrayan.spector.memory.synapse.SynapticTagEncoder
 */
@FunctionalInterface
public interface TagExtractor {

    /**
     * Extracts synaptic tags from a document's identity and content.
     *
     * <p>The returned tags are hashed into a 64-bit Bloom filter via
     * {@link com.spectrayan.spector.memory.synapse.SynapticTagEncoder}.
     * Per the analysis doc §19, optimal performance is 5–10 tags per record
     * (FPR &lt; 0.2%). Up to 50 tags is acceptable (FPR ~12%).</p>
     *
     * @param id   the document or chunk ID (may contain path segments)
     * @param text the text content of the chunk
     * @return array of tag strings (may be empty, must not be null)
     */
    String[] extract(String id, String text);

    /**
     * A no-op extractor that returns empty tags (disables Bloom filter gating).
     */
    TagExtractor NONE = (id, text) -> new String[0];
}
