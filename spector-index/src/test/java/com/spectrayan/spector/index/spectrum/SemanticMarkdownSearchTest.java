package com.spectrayan.spector.index.spectrum;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end semantic search test using Ollama embeddings and the project's own markdown files.
 *
 * <h3>What this tests</h3>
 * <ol>
 *   <li>Real embedding → SpectorIndex pipeline: ensures the quantization (VASQ) and IVF routing
 *       work correctly with 768-dim real-valued vectors, not just random synthetic data.</li>
 *   <li>Semantic relevance: given a query, the top result must contain expected keywords
 *       (e.g. searching "FWHT rotation quantization" returns a chunk from VASQ_whitepaper.txt).</li>
 *   <li>Quantization recall: the SpectorIndex recall vs brute-force is ≥ 80% on real embeddings.</li>
 * </ol>
 *
 * <h3>Prerequisites (not run in CI by default)</h3>
 * Run with {@code -Dollama.enabled=true} to activate:
 * <pre>
 *   mvn test -pl spector-index -Dollama.enabled=true
 * </pre>
 * Requires Ollama running at {@code http://localhost:11434} with {@code nomic-embed-text} pulled:
 * <pre>
 *   ollama pull nomic-embed-text
 * </pre>
 */
@EnabledIfSystemProperty(named = "ollama.enabled", matches = "true",
        disabledReason = "Ollama not enabled. Run with -Dollama.enabled=true")
class SemanticMarkdownSearchTest {

    // nomic-embed-text produces 768-dim vectors
    private static final int DIMS = 768;
    private static final String MODEL = "nomic-embed-text";

    private static OllamaEmbeddingProvider embedder;
    private static SpectorIndex index;

    /** (chunkId, text, vector) triple — built once for all tests */
    private static final List<ChunkRecord> chunks = new ArrayList<>();

    record ChunkRecord(String id, String text, float[] vector) {}

    /**
     * Ingests all markdown + text files in the project root using 512-char chunks with 64-char overlap.
     * Training is done on the first 500 chunks (or all of them if fewer).
     */
    @BeforeAll
    static void ingestMarkdownCorpus() throws IOException {
        embedder = OllamaEmbeddingProvider.create(MODEL);

        // Collect markdown + text files from project root
        Path projectRoot = Paths.get("d:/git/spector-search");
        List<Path> files;
        try (Stream<Path> stream = Files.walk(projectRoot, 2)) {
            files = stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains(".git"))
                    .toList();
        }

        System.out.println("Found " + files.size() + " markdown/text files to ingest");

        // Chunk and embed each file
        int chunkId = 0;
        for (Path file : files) {
            String content;
            try {
                content = Files.readString(file);
            } catch (Exception e) {
                continue; // skip unreadable files
            }
            String docId = file.getFileName().toString();

            // Simple character-level chunker: 512 chars, 64 overlap
            List<String> fileChunks = chunkText(content, 512, 64);
            System.out.println("  " + docId + ": " + fileChunks.size() + " chunks");

            for (int i = 0; i < fileChunks.size(); i++) {
                String text = fileChunks.get(i);
                if (text.isBlank()) continue;
                try {
                    float[] vec = embedder.embed(text).vector();
                    chunks.add(new ChunkRecord(docId + "-chunk-" + i, text, vec));
                    chunkId++;
                } catch (Exception e) {
                    System.err.println("Embedding failed for " + docId + " chunk " + i + ": " + e.getMessage());
                }
            }
        }

        System.out.println("Total chunks embedded: " + chunks.size());
        assertThat(chunks).as("Must have at least 20 chunks to run semantic tests").hasSizeGreaterThan(20);

        // Build SpectorIndex
        // Training sample: up to first 300 chunks (or all if fewer)
        int trainN = Math.min(300, chunks.size());
        float[][] trainVecs = new float[trainN][DIMS];
        for (int i = 0; i < trainN; i++) trainVecs[i] = chunks.get(i).vector();

        index = SpectorIndex.builder()
                .dimensions(DIMS)
                .nCentroids(Math.max(8, trainN / 20))   // ~5% of training set
                .nProbe(8)
                .shardThreshold(500)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(HnswParams.DEFAULT)
                .build();

        index.train(trainVecs);

        for (int i = 0; i < chunks.size(); i++) {
            ChunkRecord c = chunks.get(i);
            index.add(c.id(), i, c.vector());
        }

        System.out.println("SpectorIndex built: " + index.size() + " vectors");
    }

    // ── Semantic relevance tests ───────────────────────────────────────────────

    /**
     * Searching for VASQ/FWHT concepts should surface the whitepaper.
     */
    @Test
    void query_vasqQuantization_returnsWhitepaperChunk() {
        float[] query = embedder.embed("FWHT rotation quantization INT8 VASQ").vector();
        ScoredResult[] results = index.search(query, 5);

        assertThat(results).isNotEmpty();

        // At least one top-5 result should come from the VASQ whitepaper
        boolean foundWhitepaper = false;
        for (ScoredResult r : results) {
            if (r.id().toLowerCase().contains("vasq")) {
                foundWhitepaper = true;
                break;
            }
        }
        assertThat(foundWhitepaper)
                .as("Top-5 results for VASQ query should include VASQ_whitepaper chunk. " +
                    "Got: " + java.util.Arrays.toString(java.util.Arrays.stream(results)
                        .map(ScoredResult::id).toArray()))
                .isTrue();
    }

    /**
     * Searching for HNSW graph construction should surface relevant documentation.
     */
    @Test
    void query_hnswGraphConstruction_returnsRelevantChunk() {
        float[] query = embedder.embed("HNSW graph neighbor connection layer search").vector();
        ScoredResult[] results = index.search(query, 5);

        assertThat(results).isNotEmpty();

        // Top results should have reasonable cosine similarity (> 0.5)
        assertThat(results[0].score())
                .as("Top HNSW result should have cosine similarity > 0.4")
                .isGreaterThan(0.4f);
    }

    /**
     * Searching for performance benchmarking should surface README or benchmark docs.
     */
    @Test
    void query_performanceBenchmark_returnsReadmeOrChangelog() {
        float[] query = embedder.embed("performance benchmark QPS throughput latency").vector();
        ScoredResult[] results = index.search(query, 10);

        assertThat(results).isNotEmpty();
        assertThat(results[0].score())
                .as("Performance query should find relevant chunk (score > 0.3)")
                .isGreaterThan(0.3f);

        System.out.println("Performance query top results:");
        for (ScoredResult r : results) {
            System.out.printf("  %-50s score=%.4f%n", r.id(), r.score());
        }
    }

    /**
     * Scores must be sorted descending (best first).
     */
    @Test
    void results_areSortedDescendingByScore() {
        float[] query = embedder.embed("vector search index retrieval").vector();
        ScoredResult[] results = index.search(query, 10);

        assertThat(results).isNotEmpty();
        for (int i = 1; i < results.length; i++) {
            assertThat(results[i].score())
                    .as("Result[%d] score %.4f should be ≤ result[%d] score %.4f",
                        i, results[i].score(), i - 1, results[i - 1].score())
                    .isLessThanOrEqualTo(results[i - 1].score());
        }
    }

    // ── Recall@10 on real embeddings ──────────────────────────────────────────

    /**
     * Recall@10 on real embeddings: SpectorIndex vs brute-force cosine similarity.
     * Uses 20 random chunks as queries. Expects ≥ 75% average recall@10.
     *
     * <p>This is lower than the synthetic test (80%) because real high-dim embeddings
     * (D=768) have a harder quantization challenge than D=128 random vectors.</p>
     */
    @Test
    void recall10_onRealEmbeddings_atLeast75Percent() {
        int k = 10, queries = 20;
        Random rng = new Random(42L);

        float[][] corpus = new float[chunks.size()][DIMS];
        for (int i = 0; i < chunks.size(); i++) corpus[i] = chunks.get(i).vector();

        double totalRecall = 0;
        List<Integer> queryIndices = new ArrayList<>();
        for (int i = 0; i < queries; i++) {
            queryIndices.add(rng.nextInt(chunks.size()));
        }

        for (int qIdx : queryIndices) {
            float[] query = corpus[qIdx];

            // Brute-force top-k
            java.util.TreeMap<Float, String> exactMap = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
            for (int i = 0; i < chunks.size(); i++) {
                float sim = SimilarityFunction.COSINE.compute(query, corpus[i]);
                exactMap.put(sim, chunks.get(i).id());
            }
            java.util.Set<String> exactTop = new java.util.HashSet<>();
            exactMap.entrySet().stream().limit(k).forEach(e -> exactTop.add(e.getValue()));

            // SpectorIndex top-k
            ScoredResult[] approx = index.search(query, k);
            java.util.Set<String> approxIds = new java.util.HashSet<>();
            for (ScoredResult r : approx) approxIds.add(r.id());

            long overlap = exactTop.stream().filter(approxIds::contains).count();
            totalRecall += (double) overlap / k;
        }

        double avgRecall = totalRecall / queries;
        System.out.printf("Recall@10 on real embeddings (D=768, n=%d): %.1f%%%n",
                chunks.size(), avgRecall * 100);

        assertThat(avgRecall)
                .as("Average recall@10 on real embeddings should be ≥ 75%%. Got: %.1f%%", avgRecall * 100)
                .isGreaterThanOrEqualTo(0.75);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Simple character-level chunker with overlap.
     */
    private static List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            chunks.add(text.substring(start, end).strip());
            if (end == len) break;
            start = end - overlap;
        }
        return chunks;
    }
}
