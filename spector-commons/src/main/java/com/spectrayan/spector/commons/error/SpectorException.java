package com.spectrayan.spector.commons.error;

/**
 * Abstract base for all Spector exceptions.
 *
 * <p>Every Spector exception carries an {@link ErrorCode} that uniquely identifies the
 * error condition. The error code follows the {@code SPE-XXX-YYY} schema and is
 * <b>immutable once assigned</b> — users and monitoring systems can safely key on it.</p>
 *
 * <h3>Exception Hierarchy</h3>
 * <pre>{@code
 *   SpectorException
 *   ├── SpectorValidationException      (SPE-100-xxx)
 *   ├── SpectorConfigException          (SPE-110-xxx)
 *   ├── SpectorIndexException           (SPE-200-xxx)
 *   ├── SpectorStorageException         (SPE-210-xxx)
 *   ├── SpectorEmbeddingException       (SPE-300-xxx)
 *   ├── SpectorMemoryException          (SPE-310-xxx)
 *   ├── SpectorGpuException             (SPE-400-xxx)
 *   ├── SpectorServerException          (SPE-500-xxx)
 *   ├── SpectorClientException          (SPE-510-xxx)
 *   ├── SpectorIngestionException       (SPE-600-xxx)
 *   ├── SpectorClusterException         (SPE-700-xxx)
 *   └── SpectorInternalException        (SPE-900-xxx)
 * }</pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   throw new SpectorValidationException(
 *       ErrorCode.DIMENSIONS_MISMATCH, 384, 768);
 *   // Message: "[SPE-100-002] Expected 384 dimensions but received 768"
 * }</pre>
 *
 * @see ErrorCode
 * @see ErrorCategory
 */
public abstract class SpectorException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Creates a new Spector exception with formatted message.
     *
     * @param errorCode the stable error code identifying this condition
     * @param args      values to substitute into the message template's {@code {}} placeholders
     */
    protected SpectorException(ErrorCode errorCode, Object... args) {
        super(errorCode.format(args));
        this.errorCode = errorCode;
    }

    /**
     * Creates a new Spector exception with a cause and formatted message.
     *
     * @param errorCode the stable error code identifying this condition
     * @param cause     the underlying exception that triggered this error
     * @param args      values to substitute into the message template's {@code {}} placeholders
     */
    protected SpectorException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.format(args), cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable error code for this exception.
     *
     * <p>Error codes are immutable once assigned and follow the {@code SPE-XXX-YYY}
     * schema. Users can safely build automation and alerts on these codes.</p>
     *
     * @return the error code, never null
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Returns the numeric error code for programmatic matching.
     *
     * @return the raw numeric code, e.g. {@code 100002}
     */
    public int code() {
        return errorCode.code();
    }

    /**
     * Returns the formatted error code string, e.g. {@code "SPE-100-002"}.
     *
     * @return the stable string identifier
     */
    public String codeId() {
        return errorCode.id();
    }

    /**
     * Returns the error category for broad classification.
     *
     * @return the category this error belongs to
     */
    public ErrorCategory category() {
        return errorCode.category();
    }
}
