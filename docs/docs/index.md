# Spector Search

**Ultra-fast, SIMD-accelerated semantic search engine built on Java Vector API + modern JVM technologies.**

## What is Spector Search?

Spector Search is a high-performance vector search engine written in Java 25 that leverages:

- **Java Vector API** (jdk.incubator.vector) for SIMD-accelerated similarity kernels
- **Panama FFM** for zero-copy memory-mapped storage and GPU interop
- **Virtual Threads** for massive concurrency in ingestion, embedding, and query execution
- **Memory-mapped ANN indexes** for instant startup and zero-GC-pressure search

## Key Features

| Feature | Description |
|---------|-------------|
| Sub-millisecond queries | HNSW vector search at 0.05ms avg latency |
| Hybrid search | Combines semantic + keyword search via RRF |
| Multi-level quantization | INT8 (4×), INT4 (8×), INT2 (16×) with configurable rescore |
| GPU acceleration | CUDA kernels via Panama FFM |
| IVF-PQ compression | 32× memory reduction for billion-scale |
| Distributed search | gRPC fan-out with consistent hash sharding |
| Zero dependencies | Pure JDK, drop-in JAR |

## Quick Links

- [Getting Started](getting-started/quickstart.md) — Build, run, and search in 5 minutes
- [API Reference](api-reference/overview.md) — All REST endpoints documented
- [Configuration](configuration/parameters.md) — Tune Spector for your workload
- [Architecture](architecture/overview.md) — Understand the system design
- [Java SDK](sdk-usage/java-client.md) — Programmatic access from Java
- [CLI Reference](cli-reference/spectorctl.md) — Command-line management
