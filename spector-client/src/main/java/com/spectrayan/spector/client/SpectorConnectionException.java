package com.spectrayan.spector.client;

/**
 * Thrown when the client cannot connect to the Spector server.
 */
public class SpectorConnectionException extends SpectorClientException {

    private final String host;
    private final int port;

    public SpectorConnectionException(String host, int port, Throwable cause) {
        super("Failed to connect to Spector at " + host + ":" + port + ": " + cause.getMessage(), cause);
        this.host = host;
        this.port = port;
    }

    /** Returns the host that was attempted. */
    public String host() {
        return host;
    }

    /** Returns the port that was attempted. */
    public int port() {
        return port;
    }
}
