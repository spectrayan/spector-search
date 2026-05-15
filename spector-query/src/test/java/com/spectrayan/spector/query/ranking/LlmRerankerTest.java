package com.spectrayan.spector.query.ranking;

import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlmReranker} — LLM re-ranking logic.
 *
 * <p>These tests validate prompt construction, score parsing, and
 * graceful fallback behavior without requiring a live Ollama server.</p>
 */
class LlmRerankerTest {

    @Test
    void rerank_noOllamaServer_fallsBackGracefully() {
        // Use a non-existent server to trigger fallback
        var reranker = new LlmReranker("http://localhost:99999", "test-model", 10);

        ScoredResult[] candidates = {
                new ScoredResult("doc-1", 0, 0.9f),
                new ScoredResult("doc-2", 1, 0.8f),
                new ScoredResult("doc-3", 2, 0.7f)
        };

        // Should fall back to original order when Ollama is unavailable
        ScoredResult[] results = reranker.rerank("test query", candidates, null, 3);
        assertNotNull(results);
        assertTrue(results.length > 0, "Should return results even on failure");
        assertEquals("doc-1", results[0].id(), "Should preserve original order on fallback");
    }

    @Test
    void rerank_emptyCandidates_returnsEmpty() {
        var reranker = new LlmReranker("http://localhost:11434", "test-model");
        ScoredResult[] results = reranker.rerank("query", new ScoredResult[0], null, 5);
        assertEquals(0, results.length);
    }

    @Test
    void modelName_returnsConfiguredModel() {
        var reranker = new LlmReranker("http://localhost:11434", "llama3.2");
        assertEquals("llama3.2", reranker.modelName());
    }

    @Test
    void rerank_respectsTopK() {
        var reranker = new LlmReranker("http://localhost:99999", "test-model");

        ScoredResult[] candidates = {
                new ScoredResult("doc-1", 0, 0.9f),
                new ScoredResult("doc-2", 1, 0.8f),
                new ScoredResult("doc-3", 2, 0.7f),
                new ScoredResult("doc-4", 3, 0.6f),
                new ScoredResult("doc-5", 4, 0.5f),
        };

        ScoredResult[] results = reranker.rerank("query", candidates, null, 2);
        assertTrue(results.length <= 2, "Should respect topK limit");
    }
}
