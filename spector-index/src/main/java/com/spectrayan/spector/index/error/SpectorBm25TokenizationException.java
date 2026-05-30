package com.spectrayan.spector.index.error;

import com.spectrayan.spector.commons.error.*;

/**
 * Exception thrown when BM25 text tokenization fails.
 *
 * @see SpectorIndexException
 */
public class SpectorBm25TokenizationException extends SpectorIndexException {

    private final String details;

    public SpectorBm25TokenizationException(String details) {
        super(ErrorCode.BM25_TOKENIZATION_FAILED, details);
        this.details = details;
    }

    public SpectorBm25TokenizationException(String details, Throwable cause) {
        super(ErrorCode.BM25_TOKENIZATION_FAILED, cause, details);
        this.details = details;
    }

    /** Returns the details of the tokenization failure. */
    public String getDetails() {
        return details;
    }
}
