package com.spectrayan.spector.cli;

import com.spectrayan.spector.client.SpectorClient;
import com.spectrayan.spector.client.SpectorConnectionException;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.time.Duration;
import com.spectrayan.spector.commons.error.SpectorInternalException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Base class for CLI subcommands. Provides access to inherited options
 * (host, port, json) and a factory method for creating the SpectorClient.
 */
abstract class BaseCommand implements Runnable {

    @CommandLine.ParentCommand
    private Object parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /**
     * Creates a SpectorClient connected to the configured host/port.
     * Uses a 10-second connect timeout to satisfy requirement 18.4.
     */
    protected SpectorClient createClient() {
        return SpectorClient.builder()
                .host(getHost())
                .port(getPort())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    protected String getHost() {
        return resolveRoot().host;
    }

    protected int getPort() {
        return resolveRoot().port;
    }

    protected boolean isJson() {
        return resolveRoot().json;
    }

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected PrintWriter err() {
        return spec.commandLine().getErr();
    }

    /**
     * Handles a connection exception by printing a user-friendly error.
     */
    protected int handleConnectionError(SpectorConnectionException e) {
        err().println("Error: Unable to connect to Spector Search at " + e.host() + ":" + e.port());
        err().println("Cause: " + e.getCause().getMessage());
        return 1;
    }

    private SpectorCtl resolveRoot() {
        // Walk up the parent chain to find root SpectorCtl
        Object current = parent;
        while (current != null) {
            if (current instanceof SpectorCtl root) {
                return root;
            }
            try {
                var field = current.getClass().getDeclaredField("parent");
                field.setAccessible(true);
                current = field.get(current);
            } catch (Exception e) {
                break;
            }
        }
        // Fallback: try the direct parent
        if (parent instanceof SpectorCtl root) {
            return root;
        }
        // Should not happen if Picocli wiring is correct
        throw new SpectorInternalException(ErrorCode.INTERNAL_ERROR, "Cannot resolve root SpectorCtl command");
    }
}
