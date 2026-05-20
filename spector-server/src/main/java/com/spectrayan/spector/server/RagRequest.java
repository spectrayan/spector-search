package com.spectrayan.spector.server;

/**
 * Request DTO for the RAG endpoint ({@code POST /api/v1/rag}).
 *
 * <p>Accepts a query string plus optional retrieval parameters.</p>
 */
public class RagRequest {

    /** The query text (1–2000 characters, required). */
    public String query;

    /** Maximum number of chunks to retrieve (1–100, default 5). */
    public Integer topK;

    /** Maximum token limit for assembled context (1–8192, default 4096). */
    public Integer tokenLimit;

    /** Search mode: "vector" or "hybrid" (default "vector"). */
    public String searchMode;
}
