# API Reference

Spector Search exposes a REST API via Javalin on port 7070 (configurable).

## Base URL

```
http://localhost:7070
```

## Authentication

When an API key is configured, include it as a header:

```
X-API-Key: your-secret-key
```

## Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| GET | `/api/v1/status` | Engine status |
| POST | `/api/v1/search` | Hybrid search (auto-detects mode) |
| POST | `/api/v1/vector-search` | Vector-only search |
| POST | `/api/v1/bm25` | Keyword-only BM25 search |
| POST | `/api/v1/hybrid` | Explicit hybrid search |
| POST | `/api/v1/rag` | RAG retrieval with context assembly |
| POST | `/api/v1/ingest` | Ingest a single document |
| POST | `/api/v1/ingest/auto` | Ingest with auto-embedding |
| POST | `/api/v1/ingest/bulk` | Bulk ingest documents |
| POST | `/api/v1/index` | Create/manage indexes |
| DELETE | `/api/v1/documents/{id}` | Delete a document |
| GET | `/api/v1/metrics` | Request metrics |

See [REST Endpoints](rest-endpoints.md) for detailed request/response schemas.
