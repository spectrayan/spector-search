package com.spectrayan.spector.commons.error;

/**
 * Exception for client SDK errors ({@code SPE-510-xxx}).
 *
 * <p>Thrown when the Spector client fails to connect, times out,
 * or receives an unparseable response from the server.</p>
 *
 * @see ErrorCode#CLIENT_CONNECTION_FAILED
 * @see ErrorCode#CLIENT_TIMEOUT
 */
public class SpectorClientException extends SpectorException {

    public SpectorClientException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorClientException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(errorCode, preformattedMessage, isPreformatted);
    }

    public SpectorClientException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
