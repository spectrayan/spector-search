package com.spectrayan.spector.commons;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Streaming chunker for very large files that cannot fit into memory.
 *
 * <p>Reads text from a {@link Reader} or file {@link Path} using a bounded
 * internal buffer, producing {@link TextChunker.Chunk} instances lazily
 * via {@link Iterator} or {@link Stream}. Only the current read buffer
 * (~2× chunk size) is held in memory at any time.</p>
 *
 * <h3>Memory guarantee</h3>
 * <p>Peak memory usage is approximately {@code 2 × chunkSize} characters,
 * regardless of the total file size. This makes it suitable for multi-GB
 * log files, corpora, and data dumps.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   try (var stream = StreamingChunker.chunkFile(path, "doc-1", 512, 64)) {
 *       stream.forEach(chunk -> engine.ingest(chunk.chunkId(), chunk.text(), embed(chunk.text())));
 *   }
 * }</pre>
 */
public final class StreamingChunker {

    private StreamingChunker() {}

    /**
     * Creates a streaming chunk iterator from a Reader.
     *
     * @param reader     the source reader (not closed by this method)
     * @param documentId parent document ID
     * @param chunkSize  target chunk size in characters
     * @param overlap    overlap between chunks in characters
     * @return an iterator of chunks
     */
    public static Iterator<TextChunker.Chunk> chunkIterator(
            Reader reader, String documentId, int chunkSize, int overlap) {
        if (chunkSize <= 0) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "chunkSize", 1, Integer.MAX_VALUE, 0);
        if (overlap < 0 || overlap >= chunkSize) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "overlap", 0, 0, 0);
        return new StreamingChunkIterator(reader, documentId, chunkSize, overlap);
    }

    /**
     * Creates a Stream of chunks from a file path. The stream must be closed
     * after use (e.g., via try-with-resources) to release the file handle.
     *
     * @param path       path to the text file
     * @param documentId parent document ID
     * @param chunkSize  target chunk size in characters
     * @param overlap    overlap in characters
     * @return a closeable stream of chunks
     * @throws IOException if the file cannot be opened
     */
    public static Stream<TextChunker.Chunk> chunkFile(
            Path path, String documentId, int chunkSize, int overlap) throws IOException {
        return chunkFile(path, documentId, chunkSize, overlap, StandardCharsets.UTF_8);
    }

    /**
     * Creates a Stream of chunks from a file with the given charset.
     *
     * @param path       path to the text file
     * @param documentId parent document ID
     * @param chunkSize  target chunk size in characters
     * @param overlap    overlap in characters
     * @param charset    file encoding
     * @return a closeable stream of chunks
     * @throws IOException if the file cannot be opened
     */
    public static Stream<TextChunker.Chunk> chunkFile(
            Path path, String documentId, int chunkSize, int overlap, Charset charset) throws IOException {
        BufferedReader reader = Files.newBufferedReader(path, charset);
        var iterator = new StreamingChunkIterator(reader, documentId, chunkSize, overlap);
        var spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> {
                    try { reader.close(); } catch (IOException ignored) {}
                });
    }

    /**
     * Creates a Stream of chunks from an InputStream.
     *
     * @param inputStream the source stream
     * @param documentId  parent document ID
     * @param chunkSize   target chunk size in characters
     * @param overlap     overlap in characters
     * @return a closeable stream of chunks
     */
    public static Stream<TextChunker.Chunk> chunkStream(
            InputStream inputStream, String documentId, int chunkSize, int overlap) {
        var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        var iterator = new StreamingChunkIterator(reader, documentId, chunkSize, overlap);
        var spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> {
                    try { reader.close(); } catch (IOException ignored) {}
                });
    }

    // ─────────────── Streaming Iterator ───────────────

    private static class StreamingChunkIterator implements Iterator<TextChunker.Chunk> {

        private final Reader reader;
        private final String documentId;
        private final int chunkSize;
        private final int overlap;
        private final char[] readBuffer;

        private final StringBuilder window = new StringBuilder();
        private int chunkIndex = 0;
        private int globalCharOffset = 0;  // tracks position in original file
        private boolean readerExhausted = false;
        private TextChunker.Chunk nextChunk;

        StreamingChunkIterator(Reader reader, String documentId, int chunkSize, int overlap) {
            this.reader = reader;
            this.documentId = documentId;
            this.chunkSize = chunkSize;
            this.overlap = overlap;
            this.readBuffer = new char[chunkSize]; // read in chunk-sized blocks
        }

        @Override
        public boolean hasNext() {
            if (nextChunk != null) return true;
            nextChunk = readNextChunk();
            return nextChunk != null;
        }

        @Override
        public TextChunker.Chunk next() {
            if (!hasNext()) throw new NoSuchElementException();
            var result = nextChunk;
            nextChunk = null;
            return result;
        }

        private TextChunker.Chunk readNextChunk() {
            // Fill window until we have enough data or reader is exhausted
            fillWindow();

            if (window.isEmpty()) return null;

            // Determine chunk end
            int endPos;
            if (window.length() <= chunkSize) {
                // Everything fits in one chunk
                endPos = window.length();
            } else {
                // Find best sentence boundary before chunkSize
                endPos = findSentenceBreak(window, chunkSize);
            }

            // This is the final chunk if reader is done and we're consuming everything remaining
            boolean isLastChunk = readerExhausted && endPos >= window.length();

            String chunkText = window.substring(0, endPos).trim();
            if (chunkText.isEmpty()) {
                // Consume and retry
                int consume = Math.max(1, endPos);
                globalCharOffset += consume;
                window.delete(0, consume);
                return readNextChunk();
            }

            int startChar = globalCharOffset;
            int endChar = globalCharOffset + endPos;

            var chunk = new TextChunker.Chunk(
                    documentId,
                    documentId + "#chunk-" + chunkIndex,
                    chunkIndex,
                    chunkText,
                    startChar,
                    endChar
            );
            chunkIndex++;

            if (isLastChunk) {
                // No more data — consume everything to stop iteration
                globalCharOffset += window.length();
                window.setLength(0);
            } else {
                // Advance: consume (endPos - overlap) characters from window
                int step = endPos - overlap;
                int advance = Math.max(1, step);
                globalCharOffset += advance;
                window.delete(0, advance);
            }

            return chunk;
        }

        private void fillWindow() {
            while (!readerExhausted && window.length() < chunkSize * 2) {
                try {
                    int read = reader.read(readBuffer);
                    if (read == -1) {
                        readerExhausted = true;
                        break;
                    }
                    window.append(readBuffer, 0, read);
                } catch (IOException e) {
                    readerExhausted = true;
                    break;
                }
            }
        }

        /**
         * Finds the best sentence-ending position before maxPos.
         * Falls back to word boundary, then to maxPos.
         */
        private static int findSentenceBreak(CharSequence text, int maxPos) {
            // Scan backwards for sentence-ending punctuation followed by space
            for (int i = Math.min(maxPos, text.length()) - 1; i > maxPos / 2; i--) {
                char c = text.charAt(i);
                if ((c == '.' || c == '!' || c == '?' || c == '\n') && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (Character.isWhitespace(next) || Character.isUpperCase(next)) {
                        return i + 1;
                    }
                }
            }

            // Fall back to word boundary (space)
            for (int i = Math.min(maxPos, text.length()) - 1; i > maxPos / 2; i--) {
                if (Character.isWhitespace(text.charAt(i))) {
                    return i + 1;
                }
            }

            // No good break point — hard cut at maxPos
            return Math.min(maxPos, text.length());
        }
    }
}
