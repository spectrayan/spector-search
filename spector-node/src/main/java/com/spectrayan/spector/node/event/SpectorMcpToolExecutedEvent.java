package com.spectrayan.spector.node.event;

import java.time.Instant;

/** Fired when an MCP tool is invoked by a connected client. */
public record SpectorMcpToolExecutedEvent(
        String nodeId, Instant timestamp,
        String clientId, String toolName, long executionMs
) implements SpectorEvent {
    @Override public String eventType() { return "mcp.tool_executed"; }
}
