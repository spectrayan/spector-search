package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when an MCP client connects via SSE transport. */
public record SpectorMcpClientConnectedEvent(
        String nodeId, Instant timestamp,
        String clientId, String remoteAddress
) implements SpectorEvent {
    @Override public String eventType() { return "mcp.client_connected"; }
}
