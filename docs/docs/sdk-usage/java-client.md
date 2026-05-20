# Java Client SDK

The `spector-client` module provides a type-safe Java client for interacting with a Spector Search server.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.spectrayan</groupId>
    <artifactId>spector-client</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Creating a Client

Use the builder pattern to configure the client:

```java
SpectorClient client = SpectorClient.builder()
    .host("localhost")
    .port(7070)
    .apiKey("my-secret-key")  // optional
    .build();
```

## Runnable SDK Example

This complete example demonstrates the full lifecycle — ingest, search, and delete:

```java
import com.spectrayan.spector.client.SpectorClient;
import com.spectrayan.spector.client.model.*;

public class SpectorClientExample {
    public static void main(String[] args) throws Exception {
        // 1. Create client
        try (SpectorClient client = SpectorClient.builder()
                .host("localhost")
                .port(7070)
                .build()) {

            // 2. Ingest a document
            IngestResponse ingestResp = client.ingest(IngestRequest.builder()
                .id("sdk-doc-1")
                .title("Vector Search")
                .content("Spector uses HNSW for approximate nearest neighbor search")
                .vector(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f})
                .build());
            System.out.println("Ingested: " + ingestResp.id());

            // 3. Search
            SearchResponse searchResp = client.search(SearchRequest.builder()
                .text("nearest neighbor")
                .topK(5)
                .build());
            for (SearchResponse.Result result : searchResp.results()) {
                System.out.printf("  %s → %.4f%n", result.id(), result.score());
            }

            // 4. Check status
            StatusResponse status = client.status();
            System.out.println("Engine status: " + status.status());

            // 5. Get metrics
            MetricsResponse metrics = client.metrics();
            System.out.println("Total queries: " + metrics.totalQueries());

            // 6. Delete
            client.delete("sdk-doc-1");
            System.out.println("Deleted sdk-doc-1");
        }
    }
}
```

## Bulk Ingestion

```java
List<IngestRequest> docs = List.of(
    IngestRequest.builder().id("d1").content("first").vector(vec1).build(),
    IngestRequest.builder().id("d2").content("second").vector(vec2).build()
);
IngestResponse resp = client.bulkIngest(docs);
```

## Error Handling

The SDK throws typed exceptions:

| Exception | Cause |
|-----------|-------|
| `SpectorConnectionException` | Server unreachable |
| `SpectorApiException` | HTTP 4xx/5xx response |
| `SpectorTimeoutException` | Request timeout exceeded |

```java
try {
    client.search(request);
} catch (SpectorApiException e) {
    System.err.println("HTTP " + e.statusCode() + ": " + e.message());
} catch (SpectorConnectionException e) {
    System.err.println("Cannot connect to " + e.endpoint());
}
```

## Thread Safety

`SpectorClient` is thread-safe. It uses Java's `HttpClient` with a connection pool (default 10 connections). You can safely share a single instance across multiple threads.

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `host` | localhost | Server hostname |
| `port` | 7070 | Server port |
| `apiKey` | — | Authentication key |
| `connectTimeout` | 10s | Connection timeout |
| `requestTimeout` | 30s | Request timeout |
| `maxConnections` | 10 | Connection pool size |
