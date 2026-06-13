# ═══════════════════════════════════════════════════════════════════
# Spector — Single Docker Image
# ═══════════════════════════════════════════════════════════════════
#
# Packages everything in one image:
#   - SpectorNode backend (REST/gRPC/MCP-SSE/Prometheus) on :7070
#   - Nginx serving Angular dashboard + reverse proxy on :3000 (mapped to :7700 externally)
#
# Build:
#   docker build -f deploy/docker/Dockerfile -t spector .
#
# ═══════════════════════════════════════════════════════════════════

# ── Stage 1: Build backend ────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build

# Install Maven
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz \
       | tar xz -C /opt \
    && ln -s /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .

# Build the full reactor (skip tests for speed)
# BuildKit cache mount avoids re-downloading on retries
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn install -DskipTests -B -q \
    && mkdir -p /app/lib \
    && cp spector-node/target/spector-node-*.jar /app/spector-node.jar \
    && mvn dependency:copy-dependencies -pl spector-node \
         -DoutputDirectory=/app/lib -DincludeScope=runtime -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

# Install Nginx
RUN apk add --no-cache nginx

# Create non-root user
RUN addgroup -S spector && adduser -S spector -G spector

WORKDIR /app

# Copy backend
COPY --from=build /app/spector-node.jar /app/spector-node.jar
COPY --from=build /app/lib/             /app/lib/

# Copy YAML config
COPY deploy/docker/spector-docker.yml /app/spector.yml

# Copy frontend (pre-built Angular dist)
COPY spector-cortex/dist/spector-cortex/browser/ /usr/share/nginx/html/

# Copy Nginx config
RUN rm -f /etc/nginx/http.d/default.conf
COPY deploy/docker/nginx.conf /etc/nginx/http.d/spector.conf

# Copy entrypoint
COPY deploy/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Create data directories
RUN mkdir -p /data/index /data/memory && chown -R spector:spector /data /app

# Fix Nginx PID directory permissions
RUN mkdir -p /run/nginx

EXPOSE 3000

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:7070/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
