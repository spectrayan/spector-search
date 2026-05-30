package com.spectrayan.spector.commons;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Splits large documents into overlapping chunks for indexing.
 *
 * <p>Large documents need to be chunked before ingestion because:</p>
 * <ul>
 *   <li>Embedding models have token limits (typically 512 tokens)</li>
 *   <li>BM25 scoring is diluted by very long documents</li>
 *   <li>Search results should point to relevant passages, not entire docs</li>
 * </ul>
 *
 * <h3>Strategy</h3>
 * <p>Chunks are split at sentence boundaries to preserve semantic coherence.
 * Adjacent chunks overlap by a configurable number of characters to prevent
 * information loss at chunk boundaries.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var chunker = new TextChunker(512, 64);
 *   List<Chunk> chunks = chunker.chunk("doc-1", longDocument);
 *   for (Chunk c : chunks) {
 *       engine.ingest(c.chunkId(), c.text(), embeddingOf(c.text()));
 *   }
 * }</pre>
 */
public class TextChunker {

    /** Default chunk size in characters (~128 tokens ≈ 512 chars). */
    public static final int DEFAULT_CHUNK_SIZE = 512;

    /** Default overlap in characters (~16 tokens ≈ 64 chars). */
    public static final int DEFAULT_OVERLAP = 64;

    private final int chunkSize;
    private final int overlap;

    /**
     * A chunk of text from a larger document.
     *
     * @param parentId  the original document ID
     * @param chunkId   unique chunk ID (e.g., "doc-1#chunk-0")
     * @param index     zero-based chunk index
     * @param text      the chunk text
     * @param startChar character offset in the original document
     * @param endChar   end character offset (exclusive) in the original document
     */
    public record Chunk(
            String parentId,
            String chunkId,
            int index,
            String text,
            int startChar,
            int endChar
    ) {
        /** Returns the length of this chunk in characters. */
        public int length() { return text.length(); }
    }

    /**
     * Creates a chunker with the given size and overlap.
     *
     * @param chunkSize  target chunk size in characters
     * @param overlap    overlap between consecutive chunks in characters
     * @throws SpectorValidationException if overlap >= chunkSize
     */
    public TextChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "chunkSize", 1, Integer.MAX_VALUE, 0);
        if (overlap < 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "overlap", 0);
        if (overlap >= chunkSize) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "overlap", 0, 0, 0);
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /** Creates a chunker with default settings (512 chars, 64 char overlap). */
    public TextChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * Splits a document into overlapping chunks at sentence boundaries.
     *
     * @param documentId the parent document ID
     * @param text       the full document text
     * @return list of chunks (never empty for non-empty input)
     */
    public List<Chunk> chunk(String documentId, String text) {
        if (text == null || text.isBlank()) return List.of();

        // Short documents don't need chunking
        if (text.length() <= chunkSize) {
            return List.of(new Chunk(documentId, documentId + "#chunk-0", 0, text.trim(), 0, text.length()));
        }

        List<Integer> sentenceBoundaries = findSentenceBoundaries(text);
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        int startChar = 0;

        while (startChar < text.length()) {
            int targetEnd = Math.min(startChar + chunkSize, text.length());

            // Find the best sentence boundary before targetEnd
            int endChar = findBestBreak(sentenceBoundaries, startChar, targetEnd, text.length());

            String chunkText = text.substring(startChar, endChar).trim();
            if (!chunkText.isEmpty()) {
                String chunkId = documentId + "#chunk-" + chunkIndex;
                chunks.add(new Chunk(documentId, chunkId, chunkIndex, chunkText, startChar, endChar));
                chunkIndex++;
            }

            // Advance with overlap
            int step = endChar - startChar;
            if (step <= 0) step = chunkSize; // safety: prevent infinite loop
            startChar = endChar - overlap;
            if (startChar >= text.length()) break;
            if (startChar < 0) startChar = 0;

            // If we'd re-emit the same start, force forward
            if (chunks.size() > 1 && startChar <= chunks.get(chunks.size() - 1).startChar()) {
                startChar = endChar;
            }
        }

        return chunks;
    }

    /**
     * Splits structured content (XML/JSON/Java) into chunks.
     * First extracts text, then chunks it.
     *
     * @param documentId the parent document ID
     * @param content    structured content (XML, JSON, etc.)
     * @return list of chunks
     */
    public List<Chunk> chunkStructured(String documentId, String content) {
        String extracted = ContentExtractor.extract(content);
        return chunk(documentId, extracted);
    }

    /**
     * Returns the configured chunk size.
     *
     * @return chunk size in characters
     */
    public int chunkSize() { return chunkSize; }

    /**
     * Returns the configured overlap.
     *
     * @return overlap in characters
     */
    public int overlap() { return overlap; }

    // ─────────────── Sentence boundary detection ───────────────

    private static List<Integer> findSentenceBoundaries(String text) {
        List<Integer> boundaries = new ArrayList<>();
        BreakIterator iter = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iter.setText(text);

        int pos = iter.first();
        while (pos != BreakIterator.DONE) {
            boundaries.add(pos);
            pos = iter.next();
        }
        return boundaries;
    }

    private int findBestBreak(List<Integer> boundaries, int start, int targetEnd, int textLength) {
        if (targetEnd >= textLength) return textLength;

        // Find the last sentence boundary <= targetEnd
        int bestBreak = targetEnd;
        for (int i = boundaries.size() - 1; i >= 0; i--) {
            int boundary = boundaries.get(i);
            if (boundary <= targetEnd && boundary > start) {
                bestBreak = boundary;
                break;
            }
        }
        return bestBreak;
    }
}
