package com.spectrayan.spector.commons.error;

/**
 * Exception thrown when a request cannot be routed to the appropriate shard in the cluster.
 *
 * @see SpectorClusterException
 */
public class SpectorClusterRoutingException extends SpectorClusterException {

    private final String details;

    public SpectorClusterRoutingException(String details) {
        super(ErrorCode.CLUSTER_ROUTING_FAILED, details);
        this.details = details;
    }

    public SpectorClusterRoutingException(String details, Throwable cause) {
        super(ErrorCode.CLUSTER_ROUTING_FAILED, cause, details);
        this.details = details;
    }

    /** Returns details of the cluster routing failure. */
    public String getDetails() {
        return details;
    }
}
