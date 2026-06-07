---
title: "RAG Pipeline — Retrieval-Augmented Generation"
description: "Spector's RAG pipeline: hybrid search, RRF fusion, LLM re-ranking, context window management, and integration with Spring AI."
---

# 🤖 RAG Pipeline

> **End-to-end Retrieval-Augmented Generation built right into Spector.** From document ingestion to LLM-ready context assembly — with token-aware chunking, parallel embedding, and source attribution out of the box.

---

## Module: `spector-rag`

The RAG pipeline is a standalone module (`spector-rag`) that can be used independently or through the engine facade. It orchestrates the full flow: query embedding → retrieval → context assembly → attribution.

**Key classes:**

| Class | Purpose |
|-------|---------|
| `RagPipeline` | End-to-end orchestrator |
| `ContextBuilder` | Token-budget-aware context assembly |
| `RagRequest` / `RagResponse` | Clean input/output types |
| `ScoredChunk` | Chunk + relevance score |
| `ChunkAttribution` | Source provenance tracking |

```java
// Standalone usage (no engine facade required)
var pipeline = new RagPipeline(searchOrchestrator, documentStore, embeddingProvider);
RagResponse response = pipeline.execute(new RagRequest("What is HNSW?"));
// response.contextText() → assembled context for LLM
// response.attributions() → source document references
```

> [!NOTE]
> The `spector-rag` module uses virtual threads for the embedding call and synchronous search for retrieval. No reactive framework needed — the JDK handles async I/O natively.

---

## 🔄 Pipeline Overview

```mermaid
flowchart LR
    A["📄 Document Readers<br/>PDF / HTML / Markdown"] --> B["✂️ Token-Aware Chunker<br/>Sentence boundaries<br/>Configurable overlap"]
    B --> C["🧠 Parallel Embedding<br/>Batched via virtual threads<br/>Pluggable providers"]
    C --> D["📊 Index & Store<br/>HNSW + BM25 + mmap"]
    D --> E["🔍 Search & Retrieve<br/>Vector / Hybrid"]
    E --> F["📝 Context Builder<br/>Score-ranked assembly<br/>Token limit enforcement"]
    F --> G["✨ LLM-Ready Context<br/>+ Source Attributions"]
```

---

## 📄 Document Readers

The pipeline supports three document formats out of the box:

| Reader | Format | Behavior |
|--------|--------|----------|
| `PdfDocumentReader` | PDF | Extracts text, preserves paragraph boundaries |
| `HtmlDocumentReader` | HTML | Strips tags, converts headings to sections |
| `MarkdownDocumentReader` | Markdown | Preserves heading structure as delimiters |

```java
DocumentReader reader = new PdfDocumentReader();
DocumentResult result = reader.read(Path.of("whitepaper.pdf"));
// result.text() → extracted text
// result.metadata() → {sourceFile, format: "PDF", characterCount}
```

| Property | Value |
|----------|-------|
| Max file size | 100 MB |
| Max extraction time | 30 seconds per file |
| Failure isolation | Per-file (one failure doesn't halt pipeline) |
| Output | Text string + metadata |

> [!NOTE]
> Unsupported formats return a descriptive error. Corrupted files report the failure without stopping the pipeline.

---

## ✂️ Token-Aware Chunking

The `TokenAwareChunker` splits text into chunks that respect token boundaries and embedding model limits.

```mermaid
flowchart TD
    Input["📄 Input Text<br/>(long document)"] --> Split["Split Strategy"]
    Split --> S1["1️⃣ Prefer sentence boundaries"]
    Split --> S2["2️⃣ Fall back to word boundaries"]
    Split --> S3["3️⃣ Measure by token count"]
    
    S1 --> Chunks["✂️ Overlapping Chunks<br/>Each ≤ maxTokens"]
    S2 --> Chunks
    S3 --> Chunks
```

### Configuration

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `maxTokens` | 512 | 1–8192 | Max tokens per chunk |
| `overlapTokens` | 50 | 0–maxTokens-1 | Overlap between chunks |

```java
ChunkConfig config = new ChunkConfig(512, 50);
List<TextChunk> chunks = chunker.chunk(extractedText, config);
```

### Properties

- ✅ **Round-trip reconstruction** — Concatenating chunks reconstructs the original text

- ✅ **Token limit guarantee** — Every chunk has ≤ maxTokens

- ✅ **Single chunk for short text** — Returns exactly one chunk if input fits

- ✅ Empty/whitespace input returns an empty list

> [!TIP]
> Set `maxTokens` to match your embedding model's max input length. Increase `overlapTokens` (100–200) if chunks need more surrounding context for coherence.

---

## 🧠 Parallel Embedding Pipeline

The `ParallelEmbeddingPipeline` generates vector embeddings from text chunks using configurable batch parallelism.

```mermaid
flowchart LR
    subgraph "Input Chunks"
        C1[C1] & C2[C2] & C3[C3] & C4[C4] & C5[C5] & C6[C6] & C7[C7] & C8[C8]
    end

    subgraph "Virtual Thread 1"
        B1["Batch [C1-C4]<br/>→ Embedding Provider"]
    end

    subgraph "Virtual Thread 2"
        B2["Batch [C5-C8]<br/>→ Embedding Provider"]
    end

    C1 & C2 & C3 & C4 --> B1
    C5 & C6 & C7 & C8 --> B2
    
    B1 --> Out["Embeddings [E1...E8]<br/>Order preserved ✅"]
    B2 --> Out
```

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `batchSize` | 32 | 1–256 | Chunks per embedding API call |
| `maxRetries` | 3 | 0–10 | Retries for failed batches |

**Failure handling:**

- Failed batches are retried up to `maxRetries` times

- Processing continues for remaining batches even if one fails

- Input-output ordering is always preserved

---

## 📝 Context Builder

The `ContextBuilder` assembles retrieved chunks into a coherent context window for LLM prompting.

```mermaid
flowchart TD
    A["🔍 Retrieved Chunks<br/>(scored)"] --> B["Sort by relevance ↓"]
    B --> C{"Would adding next chunk<br/>exceed token limit?"}
    C -->|No| D["Add chunk to context"]
    D --> C
    C -->|Yes| E["Skip chunk"]
    E --> F["📝 Final Context<br/>+ Source Attributions"]
    D --> F
```

| Parameter | Default | Range |
|-----------|---------|-------|
| `tokenLimit` | 4096 | 256–131,072 |

**Properties:**

- Context never exceeds the configured token limit

- Chunks appear in descending relevance order

- Every included chunk has a source attribution

- Empty context (not an exception) when no chunks fit

---

## 🌐 The `/api/v1/rag` Endpoint

A single API call for retrieval-augmented generation:

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

**Request Parameters:**

| Field | Type | Default | Range | Description |
|-------|------|---------|-------|-------------|
| `query` | string | — | 1–2000 chars | The question/query |
| `topK` | int | 5 | 1–100 | Chunks to retrieve |
| `tokenLimit` | int | 4096 | 1–8192 | Max context tokens |
| `searchMode` | string | "vector" | "vector", "hybrid" | Search strategy |

**Response:**
```json
{
  "context": "HNSW builds a multi-layer graph structure where each layer contains a subset of nodes...",
  "attributions": [
    {"documentId": "architecture.md", "chunkOffset": 3},
    {"documentId": "algorithms.md", "chunkOffset": 0}
  ],
  "isEmpty": false
}
```

---

## 🎯 End-to-End Example

### 1️⃣ Ingest Documents via Ingestion Pipeline

```java
// Create pipeline with embedding provider
var pipeline = new IngestionPipeline(target, embeddingProvider);

// Single document (auto-embed)
pipeline.ingest("doc-1", "HNSW builds a multi-layer graph structure...");

// Large document (chunked, parallel embedding)
String whitepaper = Files.readString(Path.of("architecture.pdf.txt"));
IngestionResult result = pipeline.ingestChunked("whitepaper-1", whitepaper);
// result: 47 chunks stored, 0 failures, 2340ms
```

### 2️⃣ Query via RAG Pipeline

```java
// Direct usage of RagPipeline (standalone module)
var ragPipeline = new RagPipeline(searchOrchestrator, documentStore, embeddingProvider);

RagResponse response = ragPipeline.execute(
    new RagRequest("What is product quantization?", 5, 4096, "hybrid"));

System.out.println(response.contextText());     // assembled context
System.out.println(response.attributions());    // source references
System.out.println(response.queryTimeMs());     // 12ms
```

### 3️⃣ Query via REST API

```bash
curl -X POST http://localhost:7070/api/v1/rag \
  -d '{"query": "What is product quantization?", "topK": 3}'
```

### 4️⃣ Use Context with an LLM

```python
import requests

# Get context from Spector
rag_response = requests.post("http://localhost:7070/api/v1/rag", json={
    "query": "Explain product quantization",
    "topK": 5,
    "tokenLimit": 3000
}).json()

# Use with your LLM
prompt = f"""Based on the following context, answer the question.

Context:
{rag_response['context']}

Question: Explain product quantization

Answer:"""
```

> [!TIP]
> For Spring AI applications, use the `SpectorRagService` or `QuestionAnswerAdvisor` for automatic context retrieval. See [Spring AI Integration](../sdk-usage/spring-ai.md).

---

## 🔗 See Also

- [Ingestion Pipeline](ingestion-pipeline.md) — Document ingestion module

- [Spring AI Integration](../sdk-usage/spring-ai.md) — Spring AI RAG service

- [REST API Reference](../api-reference/rest-endpoints.md) — RAG endpoint details

- [Core Concepts](core-concepts.md) — Algorithms used in retrieval

- [Configuration Guide](../configuration/parameters.md) — RAG pipeline parameters