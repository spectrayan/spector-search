# 🌐 REST API Reference

> **Complete reference for all Spector REST endpoints.** The API runs on an embedded Armeria server with virtual threads, accepting and returning JSON. Every request gets its own virtual thread — no connection limits to worry about.

---

## 🔧 Base Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Base URL | `http://localhost:7070` | Configurable port |
| Content-Type | `application/json` | All requests and responses |
| Auth Header | `X-API-Key: <key>` | Optional, configured at startup |
| CORS | Enabled | All origins by default |

> [!NOTE]
> When an API key is configured, requests without a valid key receive `401 Unauthorized`.

---

## 💚 Health & Status

### `GET /health`

Quick health check for load balancers and monitoring.

```bash
curl http://localhost:7070/health
```

**Response `200`:**
```json
{"status": "UP"}
```

---

### `GET /api/v1/status`

Engine status including SIMD capabilities, GPU availability, and configuration.

```bash
curl http://localhost:7070/api/v1/status
```

**Response `200`:**
```json
{
  "status": "RUNNING",
  "simd": "AVX2 (256-bit, 8 lanes)",
  "gpuAvailable": false,
  "rerankerEnabled": false,
  "documentCount": 1250,
  "dimensions": 384,
  "capacity": 100000
}
```

---

### `GET /api/v1/metrics`

Request metrics including query counts, latencies, and throughput.

```bash
curl http://localhost:7070/api/v1/metrics
```

**Response `200`:**
```json
{
  "totalQueries": 4521,
  "totalIngestions": 1250,
  "avgLatencyMs": 0.34,
  "p99LatencyMs": 1.12,
  "queriesPerSecond": 8432.5
}
```

---

## 📥 Ingest Endpoints

### `POST /api/v1/ingest`

Ingest a single document with a pre-computed vector embedding.

```bash
curl -X POST http://localhost:7070/api/v1/ingest \
  -H "Content-Type: application/json" \
  -H "X-API-Key: my-secret-key" \
  -d '{
    "id": "doc-1",
    "title": "Java Vector API",
    "content": "SIMD-accelerated search engine on modern JVM",
    "vector": [0.1, 0.2, 0.3, 0.4, 0.5]
  }'
```

**Request Schema:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | ✅ | Unique document identifier |
| `title` | string | ❌ | Document title |
| `content` | string | ✅ | Text content for BM25 indexing |
| `vector` | float[] | ✅ | Embedding vector (must match configured dimensions) |
| `metadata` | object | ❌ | Arbitrary key-value metadata |

**Response `200`:**
```json
{"id": "doc-1", "status": "indexed"}
```

---

### `POST /api/v1/ingest/auto`

Ingest with automatic embedding generation. Requires a configured embedding provider (e.g., Ollama).

```bash
curl -X POST http://localhost:7070/api/v1/ingest/auto \
  -H "Content-Type: application/json" \
  -d '{
    "id": "doc-2",
    "title": "Panama FFM",
    "content": "Foreign Function and Memory API for zero-copy storage"
  }'
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | ✅ | Unique document identifier |
| `title` | string | ❌ | Document title |
| `content` | string | ✅ | Text content (used for both BM25 and embedding) |
| `metadata` | object | ❌ | Arbitrary key-value metadata |

---

### `POST /api/v1/ingest/bulk`

Ingest multiple documents in a single request.

```bash
curl -X POST http://localhost:7070/api/v1/ingest/bulk \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {"id": "d1", "content": "first document", "vector": [0.1, 0.2, 0.3]},
      {"id": "d2", "content": "second document", "vector": [0.4, 0.5, 0.6]}
    ]
  }'
```

**Response `200`:**
```json
{
  "indexed": 2,
  "failed": 0,
  "results": [
    {"id": "d1", "status": "indexed"},
    {"id": "d2", "status": "indexed"}
  ]
}
```

---

## 🔍 Search Endpoints

### `POST /api/v1/search`

Auto-detecting search endpoint. The mode is determined by which fields you provide:

| Fields Provided | Mode | Engine Used |
|-----------------|------|-------------|
| `text` only | 📝 KEYWORD | BM25 |
| `vector` only | 🧠 VECTOR | HNSW |
| `text` + `vector` | 🧬 HYBRID | RRF Fusion |

```bash
curl -X POST http://localhost:7070/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{
    "text": "vector search engine",
    "vector": [0.1, 0.2, 0.3, 0.4, 0.5],
    "topK": 10
  }'
```

**Request Schema:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `text` | string | ❌* | Query text for keyword search |
| `vector` | float[] | ❌* | Query vector for similarity search |
| `topK` | int | ❌ | Number of results (default: 10, max: 10000) |

> [!IMPORTANT]
> *At least one of `text` or `vector` must be provided.

**Response `200`:**
```json
{
  "results": [
    {
      "id": "doc-1",
      "score": 0.9523,
      "title": "Java Vector API",
      "content": "SIMD-accelerated search engine on modern JVM"
    }
  ],
  "searchMode": "HYBRID",
  "latencyMs": 0.47,
  "totalResults": 1
}
```

---

### `POST /api/v1/vector-search`

Explicit vector-only similarity search.

```bash
curl -X POST http://localhost:7070/api/v1/vector-search \
  -H "Content-Type: application/json" \
  -d '{"vector": [0.1, 0.2, 0.3, 0.4, 0.5], "topK": 10}'
```

### `POST /api/v1/bm25`

Explicit keyword-only BM25 search.

```bash
curl -X POST http://localhost:7070/api/v1/bm25 \
  -H "Content-Type: application/json" \
  -d '{"text": "SIMD acceleration", "topK": 10}'
```

### `POST /api/v1/hybrid`

Explicit hybrid search combining vector + keyword via RRF.

```bash
curl -X POST http://localhost:7070/api/v1/hybrid \
  -H "Content-Type: application/json" \
  -d '{"text": "vector search", "vector": [0.1, 0.2, 0.3, 0.4, 0.5], "topK": 10}'
```

---

### `GET /api/v1/search/stream` (SSE)

Streaming search via Server-Sent Events. Results are emitted one-by-one as they become available, enabling progressive display in UIs.

```bash
curl -N "http://localhost:7070/api/v1/search/stream?text=vector+search&topK=5&mode=HYBRID"
```

**Query Parameters:**

| Param | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `text` | string | ❌* | — | Query text for keyword/hybrid search |
| `vector` | string | ❌* | — | Comma-separated floats (e.g., `0.1,0.2,0.3`) |
| `topK` | int | ❌ | 10 | Number of results |
| `mode` | string | ❌ | auto-detect | `KEYWORD`, `VECTOR`, or `HYBRID` |

> [!IMPORTANT]
> *At least one of `text` or `vector` must be provided.

**Event Stream:**

```
event: result
data: {"id":"doc-1","score":0.9523,"rank":1}

event: result
data: {"id":"doc-3","score":0.8741,"rank":2}

event: result
data: {"id":"doc-7","score":0.8102,"rank":3}

event: done
data: {"totalHits":3,"queryTimeMs":12,"mode":"HYBRID"}
```

**Event Types:**

| Event | Description |
|-------|-------------|
| `result` | A single search result with id, score, and rank |
| `done` | Search complete — includes timing and metadata |
| `error` | An error occurred during search |

> [!TIP]
> Use the `EventSource` API in browsers or any SSE client library. Results stream immediately as they are scored, giving users instant feedback.

**JavaScript Example:**
```javascript
const source = new EventSource('/api/v1/search/stream?text=HNSW+algorithm&topK=5');

source.addEventListener('result', (event) => {
  const result = JSON.parse(event.data);
  console.log(`#${result.rank}: ${result.id} (score: ${result.score})`);
});

source.addEventListener('done', (event) => {
  const meta = JSON.parse(event.data);
  console.log(`Search complete in ${meta.queryTimeMs}ms`);
  source.close();
});
```

---

## 🤖 RAG (Retrieval-Augmented Generation)

### `POST /api/v1/rag`

Retrieve relevant context for LLM prompting. Performs search, then assembles a context window from matching chunks.

```bash
curl -X POST http://localhost:7070/api/v1/rag \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How does HNSW indexing work?",
    "topK": 5,
    "tokenLimit": 4096,
    "searchMode": "hybrid"
  }'
```

**Request Schema:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | string | ✅ | — | Query text (1–2000 chars) |
| `topK` | int | ❌ | 5 | Results to retrieve (1–100) |
| `tokenLimit` | int | ❌ | 4096 | Max context tokens (1–8192) |
| `searchMode` | string | ❌ | "vector" | `"vector"` or `"hybrid"` |

**Response `200`:**
```json
{
  "context": "Assembled context text from relevant document chunks...",
  "attributions": [
    {"documentId": "doc-1", "chunkOffset": 0},
    {"documentId": "doc-3", "chunkOffset": 2}
  ],
  "isEmpty": false
}
```

---

## 🗑️ Document Management

### `DELETE /api/v1/documents/{id}`

Delete a document by its ID.

```bash
curl -X DELETE http://localhost:7070/api/v1/documents/doc-1
```

**Response `200`:**
```json
{"id": "doc-1", "deleted": true}
```

---

## 📊 Index Management

### `POST /api/v1/index`

Create or manage indexes.

```bash
curl -X POST http://localhost:7070/api/v1/index \
  -H "Content-Type: application/json" \
  -d '{"action": "create", "name": "my-index", "dimensions": 384}'
```

---

## ❌ Error Responses

| Status | Meaning |
|--------|---------|
| `200` | ✅ Success |
| `400` | Bad request (validation error, dimension mismatch) |
| `401` | Unauthorized (invalid or missing API key) |
| `404` | Resource not found |
| `503` | Service unavailable (embedding provider down) |

---

## 🔗 See Also

- [Getting Started](../getting-started/quickstart.md) — Quick start with curl examples

- [Java SDK Guide](../sdk-usage/java-client.md) — Type-safe programmatic access

- [CLI Reference](../cli-reference/spectorctl.md) — Command-line access to the API

- [Configuration Guide](../configuration/parameters.md) — Server and auth configuration