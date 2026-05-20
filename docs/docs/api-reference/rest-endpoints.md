# REST Endpoints

## Ingest

### POST /api/v1/ingest

Ingest a single document with a pre-computed vector.

**Request:**

```json
{
  "id": "doc-1",
  "title": "Java Vector API",
  "content": "SIMD-accelerated search engine on modern JVM",
  "vector": [0.1, 0.2, 0.3, 0.4, 0.5]
}
```

**Response (200):**

```json
{
  "id": "doc-1",
  "status": "indexed"
}
```

### POST /api/v1/ingest/bulk

Ingest multiple documents in a single request.

**Request:**

```json
{
  "documents": [
    {"id": "d1", "content": "first document", "vector": [0.1, 0.2, 0.3]},
    {"id": "d2", "content": "second document", "vector": [0.4, 0.5, 0.6]}
  ]
}
```

---

## Search

### POST /api/v1/search

Auto-detecting search. Provide `text` for keyword, `vector` for vector, or both for hybrid.

**Request:**

```json
{
  "text": "vector search engine",
  "vector": [0.1, 0.2, 0.3],
  "topK": 10
}
```

**Response (200):**

```json
{
  "results": [
    {
      "id": "doc-1",
      "score": 0.9523,
      "title": "Java Vector API",
      "content": "SIMD-accelerated search engine..."
    }
  ],
  "searchMode": "HYBRID",
  "latencyMs": 0.47
}
```

### POST /api/v1/vector-search

Vector-only similarity search.

### POST /api/v1/bm25

Keyword-only BM25 search. Only requires `text` field.

### POST /api/v1/hybrid

Explicit hybrid search combining vector + keyword via RRF.

---

## RAG

### POST /api/v1/rag

Retrieval-Augmented Generation endpoint. Retrieves relevant context for LLM prompting.

**Request:**

```json
{
  "query": "How does HNSW indexing work?",
  "topK": 5,
  "tokenLimit": 4096,
  "searchMode": "hybrid"
}
```

**Response (200):**

```json
{
  "context": "Assembled context text from relevant chunks...",
  "attributions": [
    {"documentId": "doc-1", "chunkOffset": 0},
    {"documentId": "doc-3", "chunkOffset": 2}
  ],
  "isEmpty": false
}
```

**Error Responses:**

- `400` — Missing or invalid query (must be 1–2000 chars)
- `503` — Embedding provider unavailable

---

## Index Management

### POST /api/v1/index

Create or manage indexes.

---

## Document Management

### DELETE /api/v1/documents/{id}

Delete a document by ID.

**Response (200):**

```json
{
  "id": "doc-1",
  "deleted": true
}
```

---

## Monitoring

### GET /health

Returns `200 OK` when the server is running.

### GET /api/v1/status

Engine status including SIMD capabilities, GPU availability, and reranker configuration.

### GET /api/v1/metrics

Request metrics including query counts, latencies, and throughput.

---

## Runnable REST API Example

This complete example demonstrates ingesting a document and searching for it:

```bash
# 1. Start the server (in another terminal)
mvn exec:java -pl spector-server \
  -Dexec.mainClass="com.spectrayan.spector.server.SpectorServer" \
  -Dexec.args="7070 5"

# 2. Ingest a document
curl -X POST http://localhost:7070/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "id": "readme-1",
    "title": "Spector Search",
    "content": "Ultra-fast SIMD-accelerated semantic search engine",
    "vector": [0.9, 0.1, 0.3, 0.7, 0.5]
  }'

# 3. Search for it
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "text": "fast search engine",
    "vector": [0.8, 0.2, 0.3, 0.6, 0.4],
    "topK": 5
  }'

# 4. Delete the document
curl -X DELETE http://localhost:7070/api/v1/documents/readme-1
```
