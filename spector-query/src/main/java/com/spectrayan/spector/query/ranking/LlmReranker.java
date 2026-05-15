package com.spectrayan.spector.query.ranking;

import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;

/**
 * LLM-powered re-ranker using a local Ollama server.
 *
 * <p>Uses a <b>listwise</b> ranking strategy: sends the query along with all
 * candidate documents in a single prompt, asks the LLM to rate each document's
 * relevance on a 0-10 scale. This is more efficient than N individual calls
 * and provides better cross-document comparison.</p>
 *
 * <h3>Prompt Strategy</h3>
 * <p>The prompt follows a structured template:</p>
 * <ol>
 *   <li>System instruction: "You are a relevance scoring system."</li>
 *   <li>Query and numbered documents are presented.</li>
 *   <li>LLM responds with one score per line: "1: 8.5"</li>
 * </ol>
 *
 * <h3>Performance</h3>
 * <p>Latency depends on the LLM model and number of candidates.
 * Typical: 200-500ms for 10-20 candidates with a 7B model on GPU.</p>
 *
 * @see Reranker
 */
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    private final String ollamaBaseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final int maxCandidates; // max docs to send to LLM (cost control)

    /**
     * Creates an LLM re-ranker.
     *
     * @param ollamaBaseUrl Ollama server URL (e.g., "http://localhost:11434")
     * @param model         model name (e.g., "llama3.2", "qwen2.5")
     * @param maxCandidates max candidates to include in the prompt
     */
    public LlmReranker(String ollamaBaseUrl, String model, int maxCandidates) {
        this.ollamaBaseUrl = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1)
                : ollamaBaseUrl;
        this.model = model;
        this.maxCandidates = maxCandidates;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        log.info("LlmReranker initialized: model={}, maxCandidates={}", model, maxCandidates);
    }

    /** Convenience constructor with defaults. */
    public LlmReranker(String ollamaBaseUrl, String model) {
        this(ollamaBaseUrl, model, 20);
    }

    @Override
    public ScoredResult[] rerank(String query, ScoredResult[] candidates,
                                  DocumentStore docStore, int topK) {
        if (candidates.length == 0) return candidates;

        int count = Math.min(candidates.length, maxCandidates);
        long startTime = System.nanoTime();

        try {
            // Build the prompt
            String prompt = buildPrompt(query, candidates, docStore, count);

            // Call Ollama
            String response = callOllama(prompt);

            // Parse scores
            float[] scores = parseScores(response, count);

            // Build re-ranked results
            ScoredResult[] reranked = new ScoredResult[count];
            for (int i = 0; i < count; i++) {
                reranked[i] = new ScoredResult(
                        candidates[i].id(), candidates[i].index(), scores[i]);
            }

            // Sort by score descending
            Arrays.sort(reranked);

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            log.debug("LLM re-ranking completed: {} candidates in {}ms", count, elapsed);

            // Return top-K
            int resultCount = Math.min(topK, reranked.length);
            return Arrays.copyOf(reranked, resultCount);

        } catch (Exception e) {
            log.warn("LLM re-ranking failed, returning original order: {}", e.getMessage());
            return Arrays.copyOf(candidates, Math.min(topK, candidates.length));
        }
    }

    @Override
    public String modelName() { return model; }

    // ─────────────── Prompt engineering ───────────────

    private String buildPrompt(String query, ScoredResult[] candidates,
                                DocumentStore docStore, int count) {
        var sb = new StringBuilder(4096);
        sb.append("You are a relevance scoring system. ")
          .append("Rate each document's relevance to the query on a scale of 0.0 to 10.0. ")
          .append("Respond ONLY with one score per line in the format: \"N: SCORE\" ")
          .append("where N is the document number and SCORE is a decimal number.\n\n");

        sb.append("Query: ").append(query).append("\n\n");
        sb.append("Documents:\n");

        for (int i = 0; i < count; i++) {
            String docText = getDocumentText(candidates[i], docStore);
            // Truncate long documents
            if (docText.length() > 500) {
                docText = docText.substring(0, 500) + "...";
            }
            sb.append(i + 1).append(". ").append(docText).append("\n\n");
        }

        sb.append("Scores:");
        return sb.toString();
    }

    private String getDocumentText(ScoredResult result, DocumentStore docStore) {
        if (docStore == null) return result.id();
        try {
            Document doc = docStore.get(result.id());
            return doc != null ? doc.content() : result.id();
        } catch (Exception e) {
            return result.id();
        }
    }

    // ─────────────── Ollama API ───────────────

    private String callOllama(String prompt) throws Exception {
        String jsonBody = """
                {"model": "%s", "prompt": "%s", "stream": false, "options": {"temperature": 0.0, "num_predict": 256}}
                """.formatted(model, escapeJson(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama returned status " + response.statusCode());
        }

        // Extract "response" field from JSON (simple parsing)
        return extractJsonField(response.body(), "response");
    }

    // ─────────────── Response parsing ───────────────

    private float[] parseScores(String response, int expectedCount) {
        float[] scores = new float[expectedCount];
        String[] lines = response.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Parse "N: SCORE" format
            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) continue;

            try {
                int docNum = Integer.parseInt(line.substring(0, colonIdx).trim());
                float score = Float.parseFloat(line.substring(colonIdx + 1).trim());
                if (docNum >= 1 && docNum <= expectedCount) {
                    scores[docNum - 1] = Math.max(0, Math.min(10, score));
                }
            } catch (NumberFormatException ignored) {
                // Skip unparseable lines
            }
        }

        return scores;
    }

    // ─────────────── JSON utilities ───────────────

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start == -1) return "";
        start += key.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && json.charAt(i - 1) != '\\') break;
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; continue; }
                    case 't' -> { sb.append('\t'); i++; continue; }
                    case '"' -> { sb.append('"'); i++; continue; }
                    case '\\' -> { sb.append('\\'); i++; continue; }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
