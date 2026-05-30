package com.spectrayan.spector.commons;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorException;

/**
 * Token-aware text chunker that splits by word/token count instead of character count.
 *
 * <p>This chunker respects actual word boundaries using {@link BreakIterator},
 * ensuring that tokens are never split mid-word. It chunks at sentence boundaries
 * when possible, falling back to word boundaries.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var chunker = new TokenChunker(128, 16);  // 128 tokens per chunk, 16 token overlap
 *   List<TextChunker.Chunk> chunks = chunker.chunk("doc-1", largeText);
 * }</pre>
 *
 * <h3>Comparison with TextChunker</h3>
 * <ul>
 *   <li>{@link TextChunker} — chunks by character count (fast, approximate)</li>
 *   <li>{@link TokenChunker} — chunks by word/token count (accurate, slightly slower)</li>
 * </ul>
 */
public class TokenChunker {

    /** Default chunk size in tokens. Typical embedding model limit. */
    public static final int DEFAULT_TOKEN_LIMIT = 128;

    /** Default overlap in tokens. */
    public static final int DEFAULT_TOKEN_OVERLAP = 16;

    private final int maxTokens;
    private final int overlapTokens;

    /**
     * Creates a token-level chunker.
     *
     * @param maxTokens     maximum tokens per chunk
     * @param overlapTokens overlap tokens between consecutive chunks
     */
    public TokenChunker(int maxTokens, int overlapTokens) {
        if (maxTokens <= 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "maxTokens", 1, Integer.MAX_VALUE, 0);
        if (overlapTokens < 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "overlapTokens", 0);
        if (overlapTokens >= maxTokens) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "overlapTokens", 0, 0, 0);
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /** Creates a chunker with defaults (128 tokens, 16 token overlap). */
    public TokenChunker() {
        this(DEFAULT_TOKEN_LIMIT, DEFAULT_TOKEN_OVERLAP);
    }

    /**
     * Splits text into token-counted chunks at sentence boundaries.
     *
     * @param documentId parent document ID
     * @param text       full document text
     * @return list of chunks
     */
    public List<TextChunker.Chunk> chunk(String documentId, String text) {
        if (text == null || text.isBlank()) return List.of();

        // Count total tokens
        int totalTokens = WordTokenizer.countTokens(text);
        if (totalTokens <= maxTokens) {
            return List.of(new TextChunker.Chunk(
                    documentId, documentId + "#chunk-0", 0, text.trim(), 0, text.length()));
        }

        // Find all sentence boundaries
        List<Integer> sentenceBounds = findSentenceBoundaries(text);
        List<SentenceInfo> sentences = buildSentenceInfos(text, sentenceBounds);

        List<TextChunker.Chunk> chunks = new ArrayList<>();
        int sentIdx = 0;
        int chunkIndex = 0;

        while (sentIdx < sentences.size()) {
            SentenceInfo first = sentences.get(sentIdx);

            // If a single sentence exceeds maxTokens, split it at word boundaries
            if (first.tokenCount > maxTokens) {
                chunkIndex = splitOversizedSentence(
                        text, first, documentId, chunks, chunkIndex);
                sentIdx++;
                continue;
            }

            int tokenCount = 0;
            int endSentIdx = sentIdx;

            // Accumulate sentences until we exceed maxTokens
            while (endSentIdx < sentences.size()) {
                int sentTokens = sentences.get(endSentIdx).tokenCount;
                if (tokenCount + sentTokens > maxTokens && tokenCount > 0) break;
                tokenCount += sentTokens;
                endSentIdx++;
            }

            // Build chunk
            int startChar = sentences.get(sentIdx).startChar;
            int endChar = (endSentIdx < sentences.size())
                    ? sentences.get(endSentIdx).startChar
                    : text.length();

            String chunkText = text.substring(startChar, endChar).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new TextChunker.Chunk(
                        documentId, documentId + "#chunk-" + chunkIndex,
                        chunkIndex, chunkText, startChar, endChar));
                chunkIndex++;
            }

            // Advance with overlap
            if (overlapTokens > 0 && endSentIdx < sentences.size()) {
                int overlapCount = 0;
                int overlapSentIdx = endSentIdx;
                while (overlapSentIdx > sentIdx && overlapCount < overlapTokens) {
                    overlapSentIdx--;
                    overlapCount += sentences.get(overlapSentIdx).tokenCount;
                }
                sentIdx = (overlapSentIdx > sentIdx) ? overlapSentIdx : endSentIdx;
            } else {
                sentIdx = endSentIdx;
            }
        }

        return chunks;
    }

    /**
     * Splits a single sentence that exceeds maxTokens into word-boundary chunks.
     */
    private int splitOversizedSentence(String fullText, SentenceInfo sent,
                                       String documentId, List<TextChunker.Chunk> chunks,
                                       int chunkIndex) {
        String sentText = fullText.substring(sent.startChar, sent.endChar);
        var tokens = WordTokenizer.tokenize(sentText);

        int tokenIdx = 0;
        while (tokenIdx < tokens.size()) {
            int endTokenIdx = Math.min(tokenIdx + maxTokens, tokens.size());
            int startChar = sent.startChar + tokens.get(tokenIdx).startChar();
            int endChar = sent.startChar + tokens.get(endTokenIdx - 1).endChar();

            String chunkText = fullText.substring(startChar, endChar).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new TextChunker.Chunk(
                        documentId, documentId + "#chunk-" + chunkIndex,
                        chunkIndex, chunkText, startChar, endChar));
                chunkIndex++;
            }

            int step = maxTokens - overlapTokens;
            tokenIdx += Math.max(1, step);
        }
        return chunkIndex;
    }

    /**
     * Returns the configured max tokens per chunk.
     */
    public int maxTokens() { return maxTokens; }

    /**
     * Returns the configured overlap in tokens.
     */
    public int overlapTokens() { return overlapTokens; }

    // ─────────────── Internal ───────────────

    private record SentenceInfo(int startChar, int endChar, int tokenCount) {}

    private static List<Integer> findSentenceBoundaries(String text) {
        List<Integer> bounds = new ArrayList<>();
        BreakIterator iter = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iter.setText(text);
        int pos = iter.first();
        while (pos != BreakIterator.DONE) {
            bounds.add(pos);
            pos = iter.next();
        }
        return bounds;
    }

    private static List<SentenceInfo> buildSentenceInfos(String text, List<Integer> bounds) {
        List<SentenceInfo> infos = new ArrayList<>();
        for (int i = 0; i < bounds.size() - 1; i++) {
            int start = bounds.get(i);
            int end = bounds.get(i + 1);
            String sentence = text.substring(start, end);
            int tokens = WordTokenizer.countTokens(sentence);
            if (tokens > 0) {
                infos.add(new SentenceInfo(start, end, tokens));
            }
        }
        return infos;
    }
}
