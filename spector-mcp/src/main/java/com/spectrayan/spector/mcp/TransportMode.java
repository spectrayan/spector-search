/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.mcp;

/**
 * Supported MCP transport modes.
 *
 * <ul>
 *   <li>{@link #STDIO} — Standard input/output (JSON-RPC over stdin/stdout).
 *       Default for local AI agents (Claude Desktop, Cursor, etc.)</li>
 *   <li>{@link #HTTP} — Streamable HTTP transport (SSE-based streaming).
 *       For cloud deployment and remote agent connections.</li>
 * </ul>
 */
public enum TransportMode {

    /** JSON-RPC over stdin/stdout — used by local agent integrations. */
    STDIO,

    /** HTTP-based streamable transport — used for cloud/remote deployments. */
    HTTP;

    /**
     * Parses a transport mode from a string (case-insensitive).
     *
     * @param value the string value ("stdio" or "http")
     * @return the parsed TransportMode
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static TransportMode fromString(String value) {
        if (value == null || value.isBlank()) return STDIO;
        return switch (value.trim().toLowerCase()) {
            case "stdio" -> STDIO;
            case "http", "streamable-http", "sse" -> HTTP;
            default -> throw new IllegalArgumentException(
                    "Unknown transport: " + value + " (expected: stdio, http)");
        };
    }
}
