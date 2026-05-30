# spector-commons 📄

> **Ingestion utilities, text tokenizers, semantic chunkers, and document content extractors for Spector.**

`spector-commons` handles the preprocessing phase of document ingestion. It parses raw file formats (HTML, PDF, plain text), extracts core text content, and chunks it using character, token-level, or streaming boundaries to fit model context windows before embedding generation.

---

## 🏗️ Core Architecture & Roles

1. **Semantic Chunkers (`TextChunker` / `TokenChunker`):** Segments large text blocks into overlapping passages to maintain query context and respect model token limits.
2. **Streaming Chunkers (`StreamingChunker`):** High-throughput chunking controller designed to ingest streams of tokens/characters with sliding context windows.
3. **Content Extraction (`ContentExtractor` / `PdfDocumentReader`):** Pure Java, zero-dependency HTML parser and PDF decoder designed to extract structured text without heavy external libraries.

---

## 🚀 Key APIs

### Token-level Overlapping Chunking
```java
String text = "Large document content...";
int maxTokens = 256;
int overlap = 32;

List<Chunk> chunks = TokenChunker.chunk(text, maxTokens, overlap);
for (Chunk chunk : chunks) {
    System.out.printf("Chunk %d (%d tokens) -> %s%n", chunk.index(), chunk.tokenCount(), chunk.text());
}
```

### Pure Java PDF Reading
```java
byte[] pdfBytes = ...;
String extractedText = PdfDocumentReader.readText(pdfBytes);
```
