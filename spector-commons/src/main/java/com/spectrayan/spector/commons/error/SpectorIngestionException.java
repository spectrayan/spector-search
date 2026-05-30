package com.spectrayan.spector.commons.error;

/**
 * Exception for document ingestion pipeline errors ({@code SPE-600-xxx}).
 *
 * <p>Thrown when document parsing, chunking, or the ingestion pipeline fails.</p>
 *
 * @see ErrorCode#INGESTION_FORMAT_UNSUPPORTED
 * @see ErrorCode#INGESTION_PIPELINE_FAILED
 */
public class SpectorIngestionException extends SpectorException {

    public SpectorIngestionException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorIngestionException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
