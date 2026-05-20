package com.spectrayan.spector.commons.document;

/**
 * Exception thrown when a document cannot be read or processed.
 *
 * <p>This exception carries information about the file that failed and the
 * nature of the failure, without terminating the pipeline.</p>
 */
public class DocumentReadException extends RuntimeException {

    private final String fileName;
    private final String reason;

    public DocumentReadException(String fileName, String reason) {
        super("Failed to read document '%s': %s".formatted(fileName, reason));
        this.fileName = fileName;
        this.reason = reason;
    }

    public DocumentReadException(String fileName, String reason, Throwable cause) {
        super("Failed to read document '%s': %s".formatted(fileName, reason), cause);
        this.fileName = fileName;
        this.reason = reason;
    }

    public String getFileName() {
        return fileName;
    }

    public String getReason() {
        return reason;
    }
}
