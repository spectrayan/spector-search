FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.title="Spector" \
      org.opencontainers.image.description="Zero-Overhead, Agent-Ready AI Memory Backbone" \
      org.opencontainers.image.url="https://github.com/spectrayan/spector" \
      org.opencontainers.image.source="https://github.com/spectrayan/spector" \
      org.opencontainers.image.vendor="Spectrayan" \
      org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app

# Copy the distribution JAR
COPY spector-dist/target/spector.jar /app/spector.jar

# Copy default config
COPY spector.yml /app/spector.yml

# MCP server port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", \
  "--add-modules", "jdk.incubator.vector", \
  "--enable-native-access=ALL-UNNAMED", \
  "--enable-preview", \
  "-jar", "/app/spector.jar", \
  "--config", "/app/spector.yml"]
