package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when a target shard is not reachable or has been decommissioned.
 *
 * @see SpectorClusterException
 */
public class SpectorShardUnavailableException extends SpectorClusterException {

    private final String shardId;

    public SpectorShardUnavailableException(String shardId) {
        super(ErrorCode.SHARD_UNAVAILABLE, shardId);
        this.shardId = shardId;
    }

    public SpectorShardUnavailableException(String shardId, Throwable cause) {
        super(ErrorCode.SHARD_UNAVAILABLE, cause, shardId);
        this.shardId = shardId;
    }

    /** Returns the ID of the shard that is unavailable. */
    public String getShardId() {
        return shardId;
    }
}
