package com.spectrayan.spector.commons.error;

/**
 * Exception for input validation failures ({@code SPE-100-xxx}).
 *
 * <p>Thrown when user-supplied arguments violate API contracts: null values,
 * out-of-range parameters, dimension mismatches, empty collections, etc.</p>
 *
 * <p>Replaces raw {@link IllegalArgumentException} throws at public API boundaries
 * with structured, identifiable error codes.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   if (dimensions < 1)
 *       throw new SpectorValidationException(ErrorCode.DIMENSIONS_INVALID, dimensions);
 *   // → "[SPE-100-001] Vector dimensions must be positive, got 0"
 *
 *   if (vector == null)
 *       throw new SpectorValidationException(ErrorCode.VECTOR_NULL);
 *   // → "[SPE-100-003] Vector must not be null"
 * }</pre>
 *
 * @see ErrorCode
 */
public class SpectorValidationException extends SpectorException {

    /**
     * Creates a validation exception with a formatted message.
     *
     * @param errorCode the validation error code (must be in the SPE-100-xxx range)
     * @param args      values to substitute into the message template
     */
    public SpectorValidationException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorValidationException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    /**
     * Creates a validation exception with a cause and formatted message.
     *
     * @param errorCode the validation error code
     * @param cause     the underlying exception
     * @param args      values to substitute into the message template
     */
    public SpectorValidationException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
