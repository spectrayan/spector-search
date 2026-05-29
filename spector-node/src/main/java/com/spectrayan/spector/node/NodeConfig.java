package com.spectrayan.spector.node;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for a Spector node instance.
 *
 * <p>Supports two deployment modes:</p>
 * <ul>
 *   <li><b>STANDALONE</b> — single node, local engine only. Default when
 *       {@code SPECTOR_SEED_NODES} is not set.</li>
 *   <li><b>CLUSTERED</b> — multi-node, gRPC fan-out, consistent hash
 *       sharding, heartbeat membership. Activated when seed nodes are
 *       provided.</li>
 * </ul>
 *
 * <h3>Environment Variables</h3>
 * <pre>
 *   SPECTOR_PORT              — HTTP + gRPC port (default 7070)
 *   SPECTOR_NODE_ID           — unique node identifier (default: hostname)
 *   SPECTOR_SEED_NODES        — comma-separated list of seed endpoints (triggers CLUSTERED mode)
 *   SPECTOR_API_KEY           — optional API key for authentication
 *   SPECTOR_MCP_ENABLED       — "false" to disable MCP-over-SSE (default: enabled)
 *   SPECTOR_DIMS              — vector dimensions (default: 384)
 *   SPECTOR_MAX_CONNECTIONS   — max server connections (default: 10000)
 *   SPECTOR_REQUEST_TIMEOUT   — request timeout in seconds (default: 30)
 *   SPECTOR_COMPRESSION       — "false" to disable gzip/brotli (default: enabled)
 *   SPECTOR_IDLE_TIMEOUT      — idle connection timeout in seconds (default: 60)
 * </pre>
 *
 * @param port                 the single port for HTTP REST + gRPC + MCP SSE + Prometheus
 * @param mode                 deployment mode
 * @param nodeId               unique node identifier
 * @param seedNodes            cluster seed endpoints (empty in standalone)
 * @param apiKey               optional API key (null = no auth)
 * @param mcpEnabled           whether to serve MCP over SSE at /mcp
 * @param dimensions           vector dimensions for the engine
 * @param maxConnections       maximum concurrent connections
 * @param requestTimeout       per-request timeout
 * @param compressionEnabled   whether to enable response compression
 * @param idleTimeout          idle connection timeout
 */
public record NodeConfig(
        int port,
        NodeMode mode,
        String nodeId,
        List<String> seedNodes,
        String apiKey,
        boolean mcpEnabled,
        int dimensions,
        int maxConnections,
        Duration requestTimeout,
        boolean compressionEnabled,
        Duration idleTimeout
) {

    /** Deployment mode. */
    public enum NodeMode {
        /** Single node — local engine, no cluster coordination. */
        STANDALONE,
        /** Multi-node — gRPC fan-out, consistent hash sharding, HA. */
        CLUSTERED
    }

    /** Default HTTP + gRPC port. */
    public static final int DEFAULT_PORT = 7070;

    /** Default vector dimensions. */
    public static final int DEFAULT_DIMENSIONS = 384;

    /** Default max connections. */
    public static final int DEFAULT_MAX_CONNECTIONS = 10_000;

    /** Default request timeout. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Default idle timeout. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Creates a NodeConfig from environment variables.
     *
     * <p>Mode is auto-detected: if {@code SPECTOR_SEED_NODES} is set,
     * the node starts in CLUSTERED mode. Otherwise, STANDALONE.</p>
     */
    public static NodeConfig fromEnv() {
        String seeds = System.getenv("SPECTOR_SEED_NODES");
        boolean clustered = seeds != null && !seeds.isBlank();

        return new NodeConfig(
                envInt("SPECTOR_PORT", DEFAULT_PORT),
                clustered ? NodeMode.CLUSTERED : NodeMode.STANDALONE,
                envOrHostname("SPECTOR_NODE_ID"),
                clustered ? List.of(seeds.split(",")) : List.of(),
                System.getenv("SPECTOR_API_KEY"),
                !"false".equalsIgnoreCase(System.getenv("SPECTOR_MCP_ENABLED")),
                envInt("SPECTOR_DIMS", DEFAULT_DIMENSIONS),
                envInt("SPECTOR_MAX_CONNECTIONS", DEFAULT_MAX_CONNECTIONS),
                Duration.ofSeconds(envInt("SPECTOR_REQUEST_TIMEOUT", (int) DEFAULT_REQUEST_TIMEOUT.toSeconds())),
                !"false".equalsIgnoreCase(System.getenv("SPECTOR_COMPRESSION")),
                Duration.ofSeconds(envInt("SPECTOR_IDLE_TIMEOUT", (int) DEFAULT_IDLE_TIMEOUT.toSeconds()))
        );
    }

    /**
     * Creates a standalone config for programmatic use.
     */
    public static NodeConfig standalone(int port, int dimensions) {
        return new NodeConfig(
                port,
                NodeMode.STANDALONE,
                resolveHostname(),
                List.of(),
                null,
                true,
                dimensions,
                DEFAULT_MAX_CONNECTIONS,
                DEFAULT_REQUEST_TIMEOUT,
                true,
                DEFAULT_IDLE_TIMEOUT
        );
    }

    /** Whether this node is in clustered mode. */
    public boolean isClustered() {
        return mode == NodeMode.CLUSTERED;
    }

    // ─────────────── Helpers ───────────────

    private static int envInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String envOrHostname(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value.trim();
        return resolveHostname();
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "spector-node-" + ProcessHandle.current().pid();
        }
    }
}
