package com.spectrayan.spector.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.spectrayan.spector.commons.WordTokenizer;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Assembles scored chunks into a coherent context string within a configured token limit.
 *
 * <p>Chunks are ordered by descending relevance score. When the total token count exceeds
 * the limit, lowest-scored chunks are removed until the remaining chunks fit. Uses
 * {@link WordTokenizer#countTokens(String)} for consistent token measurement.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   var builder = new ContextBuilder();
 *   ContextResult result = builder.build(scoredChunks, 4096);
 * }</pre>
 */
public class ContextBuilder {

    /** Minimum allowed token limit. */
    private static final int MIN_TOKEN_LIMIT = 256;

    /** Maximum allowed token limit. */
    private static final int MAX_TOKEN_LIMIT = 131_072;

    /** Separator inserted between chunks in the assembled context string. */
    private static final String CHUNK_SEPARATOR = "\n\n";

    /**
     * Builds a context string from scored chunks within the specified token limit.
     *
     * <p>Chunks are sorted by descending relevance score (original retrieval order as
     * tiebreaker for equal scores). Lowest-scored chunks are removed when the total
     * exceeds the token limit.</p>
     *
     * @param chunks     the scored chunks from retrieval
     * @param tokenLimit the maximum number of tokens allowed in the assembled context
     * @return the assembled context result with attributions
     * @throws SpectorValidationException if tokenLimit is outside the valid range [256, 131072]
     */
    public ContextResult build(List<ScoredChunk> chunks, int tokenLimit) {
        if (tokenLimit < MIN_TOKEN_LIMIT || tokenLimit > MAX_TOKEN_LIMIT) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "tokenLimit", MIN_TOKEN_LIMIT, MAX_TOKEN_LIMIT, tokenLimit);
        }

        if (chunks == null || chunks.isEmpty()) {
            return ContextResult.empty();
        }

        // Sort by descending score; stable sort preserves original retrieval order for ties
        List<ScoredChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        // Greedily include chunks from highest to lowest score, tracking token budget
        List<ScoredChunk> included = new ArrayList<>();
        int totalTokens = 0;

        for (ScoredChunk sc : sorted) {
            int chunkTokens = WordTokenizer.countTokens(sc.chunk().text());
            int separatorTokens = included.isEmpty() ? 0 : countSeparatorTokens();

            if (totalTokens + separatorTokens + chunkTokens <= tokenLimit) {
                included.add(sc);
                totalTokens += separatorTokens + chunkTokens;
            }
        }

        if (included.isEmpty()) {
            return ContextResult.empty();
        }

        // Build context text and attributions
        StringBuilder contextText = new StringBuilder();
        List<ChunkAttribution> attributions = new ArrayList<>(included.size());

        for (int i = 0; i < included.size(); i++) {
            ScoredChunk sc = included.get(i);
            if (i > 0) {
                contextText.append(CHUNK_SEPARATOR);
            }
            contextText.append(sc.chunk().text());

            String docId = sc.chunk().sourceDocId() != null
                    ? sc.chunk().sourceDocId()
                    : "unknown";
            int chunkOffset = sc.chunk().startOffset();
            attributions.add(new ChunkAttribution(docId, chunkOffset));
        }

        return new ContextResult(contextText.toString(), attributions, false);
    }

    private int countSeparatorTokens() {
        return WordTokenizer.countTokens(CHUNK_SEPARATOR);
    }
}
