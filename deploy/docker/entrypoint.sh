#!/bin/sh
# ═══════════════════════════════════════════════════════════════
# Spector — Container Entrypoint (Backend Only)
# Starts SpectorNode in foreground.
#
# The Cortex dashboard has been moved to spector-enterprise.
# This container runs the headless cognitive memory engine only.
#
# Graceful Shutdown:
#   On SIGTERM (docker stop), the JVM receives the signal directly
#   and runs its shutdown hook to persist graphs and flush data.
# ═══════════════════════════════════════════════════════════════

set -e

# Trap signals for graceful shutdown
cleanup() {
    echo "[Spector] Received shutdown signal, draining..."
    if [ -n "$JAVA_PID" ]; then
        kill -TERM "$JAVA_PID" 2>/dev/null || true
        wait "$JAVA_PID" 2>/dev/null || true
    fi
    echo "[Spector] Shutdown complete"
    exit 0
}
trap cleanup TERM INT

# Start SpectorNode in background so trap can catch signals
echo "[Spector] Starting SpectorNode (backend only, no UI)..."
echo "[Spector] API → http://localhost:7070"
java \
    --add-modules jdk.incubator.vector \
    --enable-preview \
    --enable-native-access=ALL-UNNAMED \
    -cp "/app/spector-node.jar:/app/lib/*" \
    -Dspector.config=/app/spector.yml \
    com.spectrayan.spector.node.SpectorNode &

JAVA_PID=$!

# Wait for Java process
wait "$JAVA_PID"
exit_code=$?
echo "[Spector] Java process exited with code $exit_code"
exit $exit_code
