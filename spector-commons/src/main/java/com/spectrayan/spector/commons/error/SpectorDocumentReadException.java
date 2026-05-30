package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when a document cannot be read or processed.
 *
 * <p>This exception carries information about the file that failed and the
 * nature of the failure, without terminating the pipeline.</p>
 *
 * @see SpectorIngestionException
 */
public class SpectorDocumentReadException extends SpectorIngestionException {

    private final String fileName;
    private final String reason;

    public SpectorDocumentReadException(String fileName, String reason) {
        super(ErrorCode.DOCUMENT_READ_FAILED, fileName, reason);
        this.fileName = fileName;
        this.reason = reason;
    }

    public SpectorDocumentReadException(String fileName, String reason, Throwable cause) {
        super(ErrorCode.DOCUMENT_READ_FAILED, cause, fileName, reason);
        this.fileName = fileName;
        this.reason = reason;
    }

    /** Returns the name of the file that could not be read. */
    public String getFileName() {
        return fileName;
    }

    /** Returns the reason the read failed. */
    public String getReason() {
        return reason;
    }
}
