package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when an MCP client disconnects. */
public record SpectorMcpClientDisconnectedEvent(
        String nodeId, Instant timestamp,
        String clientId
) implements SpectorEvent {
    @Override public String eventType() { return "mcp.client_disconnected"; }
}
