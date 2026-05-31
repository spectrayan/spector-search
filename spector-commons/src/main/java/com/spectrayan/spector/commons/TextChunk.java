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
package com.spectrayan.spector.commons;

/**
 * Represents a chunk of text produced by the chunking engine.
 *
 * @param text        the chunk text content
 * @param tokenCount  number of tokens in this chunk
 * @param startOffset character start offset in the original text (inclusive)
 * @param endOffset   character end offset in the original text (exclusive)
 * @param sourceDocId the source document identifier (may be null if not applicable)
 */
public record TextChunk(String text, int tokenCount, int startOffset, int endOffset, String sourceDocId) {

    /**
     * Creates a TextChunk without a source document ID.
     */
    public TextChunk(String text, int tokenCount, int startOffset, int endOffset) {
        this(text, tokenCount, startOffset, endOffset, null);
    }
}
