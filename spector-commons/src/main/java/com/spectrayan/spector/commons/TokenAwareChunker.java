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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Token-aware chunking engine that splits text into chunks respecting token boundaries.
 *
 * <p>This chunker ensures that:</p>
 * <ul>
 *   <li>No chunk exceeds the configured maximum token count</li>
 *   <li>Splitting prefers sentence boundaries for semantic coherence</li>
 *   <li>When a single sentence exceeds the max token count, it splits at word boundaries</li>
 *   <li>Configurable overlap between consecutive chunks preserves context</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var config = new ChunkConfig(512, 64);
 *   var chunker = new TokenAwareChunker();
 *   List<TextChunk> chunks = chunker.chunk("Long document text...", config);
 * }</pre>
 */
public class TokenAwareChunker {

    /**
     * Splits text into token-aware chunks according to the given configuration.
     *
     * @param text   the input text to chunk
     * @param config the chunking configuration
     * @return list of text chunks; empty list if input is null or whitespace-only
     */
    public List<TextChunk> chunk(String text, ChunkConfig config) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int totalTokens = WordTokenizer.countTokens(text);
        if (totalTokens <= config.maxTokens()) {
            return List.of(new TextChunk(text, totalTokens, 0, text.length()));
        }

        List<SentenceSpan> sentences = extractSentences(text);
        List<TextChunk> chunks = new ArrayList<>();
        int sentIdx = 0;

        while (sentIdx < sentences.size()) {
            SentenceSpan firstSent = sentences.get(sentIdx);

            // Handle oversized sentence: split at word boundaries
            if (firstSent.tokenCount() > config.maxTokens()) {
                sentIdx = splitOversizedSentence(text, firstSent, config, chunks, sentIdx);
                continue;
            }

            // Accumulate sentences up to maxTokens
            int tokenCount = 0;
            int endSentIdx = sentIdx;

            while (endSentIdx < sentences.size()) {
                SentenceSpan sent = sentences.get(endSentIdx);
                if (sent.tokenCount() > config.maxTokens()) {
                    // Next sentence is oversized, stop accumulation here
                    break;
                }
                if (tokenCount + sent.tokenCount() > config.maxTokens() && tokenCount > 0) {
                    break;
                }
                tokenCount += sent.tokenCount();
                endSentIdx++;
            }

            // Build chunk from sentIdx to endSentIdx (exclusive)
            int startChar = sentences.get(sentIdx).startChar();
            int endChar = (endSentIdx < sentences.size())
                    ? sentences.get(endSentIdx).startChar()
                    : text.length();

            String chunkText = text.substring(startChar, endChar);
            // Trim trailing whitespace but preserve the text for round-trip
            if (!chunkText.isBlank()) {
                int actualTokens = WordTokenizer.countTokens(chunkText);
                chunks.add(new TextChunk(chunkText, actualTokens, startChar, endChar));
            }

            // Advance with overlap
            if (config.overlapTokens() > 0 && endSentIdx < sentences.size()) {
                int overlapCount = 0;
                int overlapSentIdx = endSentIdx;
                while (overlapSentIdx > sentIdx && overlapCount < config.overlapTokens()) {
                    overlapSentIdx--;
                    overlapCount += sentences.get(overlapSentIdx).tokenCount();
                }
                sentIdx = Math.max(overlapSentIdx, sentIdx + 1);
            } else {
                sentIdx = endSentIdx;
            }
        }

        return chunks;
    }

    /**
     * Splits an oversized sentence at word boundaries into sub-chunks.
     *
     * @return the next sentence index to process
     */
    private int splitOversizedSentence(String fullText, SentenceSpan sent,
                                       ChunkConfig config, List<TextChunk> chunks, int sentIdx) {
        String sentText = fullText.substring(sent.startChar(), sent.endChar());
        List<WordTokenizer.Token> tokens = WordTokenizer.tokenize(sentText);

        int tokenIdx = 0;
        while (tokenIdx < tokens.size()) {
            int endTokenIdx = Math.min(tokenIdx + config.maxTokens(), tokens.size());

            int startCharInSent = tokens.get(tokenIdx).startChar();
            int endCharInSent = tokens.get(endTokenIdx - 1).endChar();

            int startChar = sent.startChar() + startCharInSent;
            int endChar = sent.startChar() + endCharInSent;

            String chunkText = fullText.substring(startChar, endChar);
            int actualTokens = endTokenIdx - tokenIdx;
            chunks.add(new TextChunk(chunkText, actualTokens, startChar, endChar));

            int step = config.maxTokens() - config.overlapTokens();
            tokenIdx += Math.max(1, step);
        }

        return sentIdx + 1;
    }

    // ─────────────── Sentence extraction ───────────────

    private record SentenceSpan(int startChar, int endChar, int tokenCount) {}

    private static List<SentenceSpan> extractSentences(String text) {
        List<SentenceSpan> sentences = new ArrayList<>();
        BreakIterator iter = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iter.setText(text);

        int start = iter.first();
        int end = iter.next();

        while (end != BreakIterator.DONE) {
            String sentence = text.substring(start, end);
            int tokenCount = WordTokenizer.countTokens(sentence);
            if (tokenCount > 0) {
                sentences.add(new SentenceSpan(start, end, tokenCount));
            }
            start = end;
            end = iter.next();
        }

        return sentences;
    }
}
