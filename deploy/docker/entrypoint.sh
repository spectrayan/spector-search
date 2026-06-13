#!/bin/sh
# ═══════════════════════════════════════════════════════════════
# Spector — Container Entrypoint
# Starts Nginx (background) + SpectorNode (foreground)
#
# Graceful Shutdown:
#   On SIGTERM (docker stop), the JVM receives the signal directly
#   and runs its shutdown hook to persist graphs and flush data.
#   Nginx is stopped first, then we wait for the JVM to finish.
# ═══════════════════════════════════════════════════════════════

set -e

# Start Nginx in background (serves dashboard + proxies API/MCP)
echo "[Spector] Starting Nginx..."
nginx

# Trap signals for graceful shutdown
cleanup() {
    echo "[Spector] Received shutdown signal, draining..."
    # Stop accepting new HTTP connections
    nginx -s quit 2>/dev/null || true
    # Forward SIGTERM to Java (triggers JVM shutdown hook)
    if [ -n "$JAVA_PID" ]; then
        kill -TERM "$JAVA_PID" 2>/dev/null || true
        # Wait for Java to finish its shutdown hook (graph persistence, etc.)
        wait "$JAVA_PID" 2>/dev/null || true
    fi
    echo "[Spector] Shutdown complete"
    exit 0
}
trap cleanup TERM INT

# Start SpectorNode in background so trap can catch signals
echo "[Spector] Starting SpectorNode..."
java \
    --add-modules jdk.incubator.vector \
    --enable-preview \
    -cp "/app/spector-node.jar:/app/lib/*" \
    -Dspector.config=/app/spector.yml \
    com.spectrayan.spector.node.SpectorNode &

JAVA_PID=$!

# Wait for Java process (if it exits on its own, we exit too)
# The 'wait' will be interrupted by SIGTERM, which triggers cleanup()
wait "$JAVA_PID"
exit_code=$?
echo "[Spector] Java process exited with code $exit_code"
exit $exit_code
