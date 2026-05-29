package com.spectrayan.spector.cluster;

/**
 * Exception thrown when a membership operation fails.
 */
public class MembershipException extends RuntimeException {

    public MembershipException(String message) {
        super(message);
    }

    public MembershipException(String message, Throwable cause) {
        super(message, cause);
    }
}
