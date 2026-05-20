# Quick Start

Get Spector Search running and execute your first search in under 5 minutes.

## Prerequisites

- **JDK 25+** (OpenJDK with Vector API incubator)
- **Maven 3.9+**

## Build

```bash
git clone https://github.com/spectrayan/spector-search.git
cd spector-search
mvn clean test
```

## Start the Server

```bash
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer"
```

The server starts on port 7070 by default.

## Ingest a Document

```bash
curl -X POST http://localhost:7070/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-1",
    "title": "Java Vector API",
    "content": "SIMD-accelerated search engine on modern JVM",
    "vector": [0.1, 0.2, 0.3, 0.4, 0.5]
  }'
```

## Search

```bash
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "text": "vector search",
    "topK": 10
  }'
```

## Next Steps

- [Installation guide](installation.md) for detailed setup options
- [API Reference](../api-reference/overview.md) for all endpoints
- [Java SDK](../sdk-usage/java-client.md) for programmatic access
