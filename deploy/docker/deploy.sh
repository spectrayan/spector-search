#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# Spector — Build & Deploy Docker Image
# ═══════════════════════════════════════════════════════════════════
#
# Usage:
#   ./deploy.sh              # Build + run (default)
#   ./deploy.sh build        # Build only
#   ./deploy.sh run          # Run only (image must exist)
#   ./deploy.sh stop         # Stop running container
#   ./deploy.sh logs         # Tail container logs
#   ./deploy.sh clean        # Stop + remove container + image
#
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────
IMAGE_NAME="spector"
CONTAINER_NAME="spector"
DOCKERFILE="deploy/docker/Dockerfile"
DATA_VOLUME="spector-data"
HOST_PORT_HTTP=7700
HOST_PORT_API=7070

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log()  { echo -e "${CYAN}[Spector]${NC} $*"; }
ok()   { echo -e "${GREEN}[Spector]${NC} $*"; }
warn() { echo -e "${YELLOW}[Spector]${NC} $*"; }
err()  { echo -e "${RED}[Spector]${NC} $*" >&2; }

# ── Navigate to project root ──────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# ── Functions ──────────────────────────────────────────────────────

build() {
    log "Building Docker image '${IMAGE_NAME}'..."

    # Check Docker is available
    if ! command -v docker &> /dev/null; then
        err "Docker is not installed or not in PATH"
        exit 1
    fi

    # Check Dockerfile exists
    if [ ! -f "$DOCKERFILE" ]; then
        err "Dockerfile not found at $DOCKERFILE"
        exit 1
    fi

    local start_time=$SECONDS

    docker build \
        -f "$DOCKERFILE" \
        -t "$IMAGE_NAME" \
        --build-arg BUILDKIT_INLINE_CACHE=1 \
        . 2>&1 | while IFS= read -r line; do
            echo "  $line"
        done

    local elapsed=$(( SECONDS - start_time ))
    ok "Image '${IMAGE_NAME}' built in ${elapsed}s"
    docker images "$IMAGE_NAME" --format "  Size: {{.Size}}  Created: {{.CreatedAt}}"
}

stop() {
    if docker ps -q -f "name=${CONTAINER_NAME}" | grep -q .; then
        log "Stopping container '${CONTAINER_NAME}'..."
        docker stop --timeout 30 "$CONTAINER_NAME" > /dev/null
        ok "Container stopped"
    fi
    if docker ps -aq -f "name=${CONTAINER_NAME}" | grep -q .; then
        docker rm "$CONTAINER_NAME" > /dev/null
        log "Removed old container"
    fi
}

run() {
    # Check image exists
    if ! docker image inspect "$IMAGE_NAME" &> /dev/null; then
        err "Image '${IMAGE_NAME}' not found. Run: $0 build"
        exit 1
    fi

    # Stop existing container
    stop

    # Create data volume if it doesn't exist
    docker volume create "$DATA_VOLUME" > /dev/null 2>&1 || true

    log "Starting container '${CONTAINER_NAME}'..."
    log "  HTTP (Nginx+Dashboard) → http://localhost:${HOST_PORT_HTTP}"
    log "  API  (SpectorNode)     → http://localhost:${HOST_PORT_API}"
    log "  Data volume            → ${DATA_VOLUME}"

    docker run -d \
        --name "$CONTAINER_NAME" \
        -p "${HOST_PORT_HTTP}:3000" \
        -p "${HOST_PORT_API}:7070" \
        -v "${DATA_VOLUME}:/data" \
        --add-host=host.docker.internal:host-gateway \
        --restart unless-stopped \
        "$IMAGE_NAME"

    # Wait for health check
    log "Waiting for health check..."
    local retries=0
    local max_retries=30
    while [ $retries -lt $max_retries ]; do
        if docker inspect --format='{{.State.Health.Status}}' "$CONTAINER_NAME" 2>/dev/null | grep -q "healthy"; then
            ok "Container is healthy!"
            return
        fi
        sleep 2
        retries=$((retries + 1))
    done

    warn "Health check timed out after $((max_retries * 2))s — container may still be starting"
    warn "Check logs: $0 logs"
}

logs() {
    if ! docker ps -q -f "name=${CONTAINER_NAME}" | grep -q .; then
        err "Container '${CONTAINER_NAME}' is not running"
        exit 1
    fi
    docker logs -f "$CONTAINER_NAME"
}

clean() {
    stop
    if docker image inspect "$IMAGE_NAME" &> /dev/null; then
        log "Removing image '${IMAGE_NAME}'..."
        docker rmi "$IMAGE_NAME" > /dev/null
        ok "Image removed"
    fi
    log "Note: Data volume '${DATA_VOLUME}' preserved. Remove with: docker volume rm ${DATA_VOLUME}"
}

# ── Main ───────────────────────────────────────────────────────────

case "${1:-}" in
    build)
        build
        ;;
    run)
        run
        ;;
    stop)
        stop
        ;;
    logs)
        logs
        ;;
    clean)
        clean
        ;;
    ""|deploy)
        build
        run
        ;;
    *)
        err "Unknown command: $1"
        echo "Usage: $0 {build|run|stop|logs|clean|deploy}"
        exit 1
        ;;
esac
