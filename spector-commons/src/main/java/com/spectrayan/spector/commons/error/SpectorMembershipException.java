package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when a cluster membership operation fails.
 *
 * @see SpectorClusterException
 */
public class SpectorMembershipException extends SpectorClusterException {

    public SpectorMembershipException(String message) {
        super(ErrorCode.CLUSTER_MEMBERSHIP_FAILED, message);
    }

    public SpectorMembershipException(String message, Throwable cause) {
        super(ErrorCode.CLUSTER_MEMBERSHIP_FAILED, cause, message);
    }
}
