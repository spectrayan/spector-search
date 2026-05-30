package com.spectrayan.spector.commons.error;

/**
 * Exception for index construction, search, persistence, and integrity errors ({@code SPE-200-xxx}).
 *
 * <p>Covers HNSW graph building, IVF training, BM25 tokenization, index serialization,
 * and structural integrity violations.</p>
 *
 * @see ErrorCode#HNSW_BUILD_FAILED
 * @see ErrorCode#INDEX_FULL
 */
public class SpectorIndexException extends SpectorException {

    public SpectorIndexException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorIndexException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorIndexException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
