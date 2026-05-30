package com.spectrayan.spector.commons.error;

/**
 * Exception for distributed cluster errors ({@code SPE-700-xxx}).
 *
 * <p>Thrown when shard routing fails, cluster membership operations fail,
 * or a target shard is unavailable in distributed mode.</p>
 *
 * @see ErrorCode#SHARD_UNAVAILABLE
 * @see ErrorCode#CLUSTER_MEMBERSHIP_FAILED
 */
public class SpectorClusterException extends SpectorException {

    public SpectorClusterException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public SpectorClusterException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
