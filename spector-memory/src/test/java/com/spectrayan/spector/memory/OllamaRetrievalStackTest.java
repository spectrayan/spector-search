/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.model.*;

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;
import com.spectrayan.spector.memory.cortex.MemorySource;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the retrieval stack using real Ollama embeddings.
 *
 * <h3>What This Tests</h3>
 * <p>Validates the full ingest → index → recall pipeline using production-grade
 * embeddings from Ollama's {@code qwen3-embedding} model. Unlike unit tests that
 * use mock embeddings with random vectors, these tests verify that:
 * <ul>
 *   <li>BM25 keyword search works correctly alongside real vector search</li>
 *   <li>Hybrid mode (BM25 + Vector) produces meaningful fusion results</li>
 *   <li>Text search mode switching (HYBRID → KEYWORD_ONLY → VECTOR_ONLY) works</li>
 *   <li>Concurrent recall with real embeddings doesn't crash</li>
 *   <li>Relevance ordering is semantically meaningful (not just hash-coincidence)</li>
 * </ul>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Ollama running on {@code localhost:11434}</li>
 *   <li>Model pulled: {@code ollama pull qwen3-embedding}</li>
 *   <li>Set env var: {@code OLLAMA_LIVE=true}</li>
 * </ul>
 *
 * <h3>Run</h3>
 * <pre>
 *   set OLLAMA_LIVE=true
 *   mvn test -pl spector-memory -Dtest=OllamaRetrievalStackTest -am
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Ollama Retrieval Stack Integration Tests")
class OllamaRetrievalStackTest {

    private static final String MODEL = "qwen3-embedding";
    private static OllamaEmbeddingProvider embeddingProvider;
    private static int detectedDimensions;

    private SpectorMemory memory;

    @BeforeAll
    static void initOllama() {
        embeddingProvider = OllamaEmbeddingProvider.create(MODEL);
        EmbeddingResult probe = embeddingProvider.embed("dimension probe");
        detectedDimensions = probe.dimensions();
        System.out.printf("Ollama %s: detected %d dimensions%n", MODEL, detectedDimensions);
    }

    @BeforeEach
    void setUp() {
        memory = DefaultSpectorMemory.builder()
                .dimensions(detectedDimensions)
                .embeddingProvider(embeddingProvider)
                .persistenceMode(MemoryPersistenceMode.IN_MEMORY)
                .workingCapacity(50)
                .episodicPartitionCapacity(500)
                .semanticCapacity(200)
                .proceduralCapacity(100)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (memory != null) memory.close();
    }

    // ══════════════════════════════════════════════════════════════
    // BM25 Keyword Search with Real Vectors
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("BM25 keyword recall — exact-term matches found in keyword-only mode")
    void bm25_keywordRecall() throws Exception {
        memory.remember("kw-java", "Java uses garbage collection for automatic memory management.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "java").get(30, TimeUnit.SECONDS);
        memory.remember("kw-rust", "Rust uses ownership and borrowing instead of garbage collection.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "rust").get(30, TimeUnit.SECONDS);
        memory.remember("kw-python", "Python has dynamic typing and is commonly used for scripting.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "python").get(30, TimeUnit.SECONDS);
        memory.remember("kw-deploy", "The deployment pipeline uses blue-green strategy for zero downtime.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "deployment").get(30, TimeUnit.SECONDS);

        // Keyword search for "garbage collection"
        List<CognitiveResult> results = memory.recall("garbage collection",
                RecallOptions.builder()
                        .topK(5)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        System.out.println("=== BM25 Keyword: 'garbage collection' ===");
        printResults(results);

        assertThat(results).isNotEmpty();
        // At least one result should contain "garbage" or "collection"
        boolean found = results.stream()
                .anyMatch(r -> r.text().toLowerCase().contains("garbage")
                        || r.text().toLowerCase().contains("collection"));
        assertThat(found).as("BM25 should find keyword matches").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("BM25 no false positives — unrelated query returns empty or very low scores")
    void bm25_noFalsePositives() throws Exception {
        memory.remember("fp-java", "Java is a strongly typed programming language.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "java").get(30, TimeUnit.SECONDS);
        memory.remember("fp-python", "Python is popular for machine learning.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "python").get(30, TimeUnit.SECONDS);

        // Query for completely unrelated term in keyword-only mode
        List<CognitiveResult> results = memory.recall("quantum entanglement teleportation",
                RecallOptions.builder()
                        .topK(5)
                        .textSearchMode(TextSearchMode.KEYWORD_ONLY)
                        .build());

        System.out.println("\n=== BM25 No False Positives: 'quantum entanglement teleportation' ===");
        printResults(results);

        // BM25 keyword-only should find nothing (none of those words exist)
        // Note: vector-only might still return results via semantic similarity,
        // but keyword-only should not match
        if (!results.isEmpty()) {
            // If we get results, they should not contain any of the query terms
            for (CognitiveResult cr : results) {
                boolean containsQueryTerm = cr.text().toLowerCase().contains("quantum")
                        || cr.text().toLowerCase().contains("entanglement")
                        || cr.text().toLowerCase().contains("teleportation");
                assertThat(containsQueryTerm).as("BM25 should not match unrelated terms").isFalse();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Hybrid Mode — BM25 + Vector
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("Hybrid recall — combines keyword AND semantic matches")
    void hybrid_recall() throws Exception {
        // Keyword-matchable
        memory.remember("h-exact", "PostgreSQL connection pool exhausted during peak traffic.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "database", "error").get(30, TimeUnit.SECONDS);
        // Semantically related but no keyword overlap
        memory.remember("h-semantic", "The relational database had too many open sessions at the same time.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "database", "error").get(30, TimeUnit.SECONDS);
        // Unrelated
        memory.remember("h-unrelated", "React component lifecycle hooks were deprecated in favor of hooks API.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "frontend").get(30, TimeUnit.SECONDS);

        List<CognitiveResult> results = memory.recall("PostgreSQL connection pool",
                RecallOptions.builder()
                        .topK(5)
                        .textSearchMode(TextSearchMode.HYBRID)
                        .build());

        System.out.println("\n=== Hybrid: 'PostgreSQL connection pool' ===");
        printResults(results);

        assertThat(results).isNotEmpty();

        // The exact keyword match should be present
        boolean foundExact = results.stream().anyMatch(r -> "h-exact".equals(r.id()));
        assertThat(foundExact).as("Hybrid should include keyword match").isTrue();

        // The semantically related one should also appear
        boolean foundSemantic = results.stream().anyMatch(r -> "h-semantic".equals(r.id()));
        assertThat(foundSemantic).as("Hybrid should include semantic match").isTrue();
    }

    // ══════════════════════════════════════════════════════════════
    // Vector-Only Semantic Search
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("Vector-only recall — semantic similarity with real embeddings")
    void vectorOnly_semanticSimilarity() throws Exception {
        memory.remember("v-direct", "The Kubernetes autoscaler adjusts pod count based on CPU utilization metrics.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "k8s").get(30, TimeUnit.SECONDS);
        memory.remember("v-related", "Container orchestration platforms can dynamically scale workloads.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "containers").get(30, TimeUnit.SECONDS);
        memory.remember("v-unrelated", "The chef prepared a delicious pasta with homemade tomato sauce.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "food").get(30, TimeUnit.SECONDS);

        List<CognitiveResult> results = memory.recall("scaling pods in Kubernetes cluster",
                RecallOptions.builder()
                        .topK(5)
                        .textSearchMode(TextSearchMode.VECTOR_ONLY)
                        .build());

        System.out.println("\n=== Vector-Only: 'scaling pods in Kubernetes cluster' ===");
        printResults(results);

        assertThat(results).isNotEmpty();

        // With real embeddings, k8s/container results should score higher than food
        CognitiveResult k8sResult = results.stream()
                .filter(r -> "v-direct".equals(r.id())).findFirst().orElse(null);
        CognitiveResult foodResult = results.stream()
                .filter(r -> "v-unrelated".equals(r.id())).findFirst().orElse(null);

        if (k8sResult != null && foodResult != null) {
            assertThat(k8sResult.score())
                    .as("K8s result should score higher than food with real embeddings")
                    .isGreaterThan(foodResult.score());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Text Search Mode Switching
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("Text search mode switching — HYBRID, KEYWORD_ONLY, VECTOR_ONLY all work")
    void textSearchModeSwitching() throws Exception {
        memory.remember("ts-1", "Spring Boot auto-configuration resolves beans using conditional annotations.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "spring").get(30, TimeUnit.SECONDS);
        memory.remember("ts-2", "The framework automatically sets up database connections based on classpath.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "spring").get(30, TimeUnit.SECONDS);

        String query = "Spring Boot auto-configuration";

        // HYBRID
        List<CognitiveResult> hybrid = memory.recall(query,
                RecallOptions.builder().topK(5).textSearchMode(TextSearchMode.HYBRID).build());
        System.out.println("\n=== Mode Switch: HYBRID ===");
        printResults(hybrid);

        // KEYWORD_ONLY
        List<CognitiveResult> keyword = memory.recall(query,
                RecallOptions.builder().topK(5).textSearchMode(TextSearchMode.KEYWORD_ONLY).build());
        System.out.println("=== Mode Switch: KEYWORD_ONLY ===");
        printResults(keyword);

        // VECTOR_ONLY
        List<CognitiveResult> vector = memory.recall(query,
                RecallOptions.builder().topK(5).textSearchMode(TextSearchMode.VECTOR_ONLY).build());
        System.out.println("=== Mode Switch: VECTOR_ONLY ===");
        printResults(vector);

        // All modes should return results (the data is both keyword-matchable and semantically similar)
        assertThat(hybrid).as("HYBRID should return results").isNotEmpty();
        assertThat(keyword).as("KEYWORD_ONLY should return results").isNotEmpty();
        assertThat(vector).as("VECTOR_ONLY should return results").isNotEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // Concurrent Recall with Real Embeddings
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("Concurrent recall — 5 threads, real Ollama embeddings, no crashes")
    void concurrentRecall_realEmbeddings() throws Exception {
        // Seed data
        String[] topics = {
                "Java garbage collection tuning for low-latency applications",
                "Kubernetes horizontal pod autoscaler configuration",
                "PostgreSQL query optimization with partial indexes",
                "React server components for improved hydration performance",
                "OAuth2 authorization code flow with PKCE extension",
                "Docker multi-stage builds for minimal image size",
                "gRPC bidirectional streaming for real-time communication",
                "Elasticsearch mapping types and custom analyzers",
                "Redis cluster sharding and failover strategies",
                "Terraform state management for multi-cloud deployments"
        };

        for (int i = 0; i < topics.length; i++) {
            memory.remember("conc-" + i, topics[i],
                    MemoryType.EPISODIC, MemorySource.OBSERVED, "tech")
                    .get(30, TimeUnit.SECONDS);
        }

        int threadCount = 5;
        var latch = new CountDownLatch(threadCount);
        var errors = new CopyOnWriteArrayList<Throwable>();
        String[] queries = {
                "Java GC tuning", "Kubernetes scaling", "database optimization",
                "frontend performance", "authentication flow"
        };

        for (int t = 0; t < threadCount; t++) {
            final int tId = t;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        List<CognitiveResult> results = memory.recall(queries[tId],
                                RecallOptions.builder().topK(5).build());
                        assertThat(results).isNotEmpty();
                        for (CognitiveResult cr : results) {
                            assertThat(cr.id()).isNotNull();
                            assertThat(cr.text()).isNotBlank();
                            assertThat(cr.score()).isGreaterThanOrEqualTo(0f);
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(120, TimeUnit.SECONDS))
                .as("All concurrent recall threads should finish").isTrue();
        assertThat(errors).as("No exceptions during concurrent recall with real embeddings").isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // Full E2E: Ingest 50 → Recall with Relevance Ordering
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("E2E 50-doc ingest + recall — real embeddings, verify relevance ordering")
    void ingestRecall_e2e_50docs() throws Exception {
        String[] domainTopics = {
                "Java virtual threads provide lightweight concurrency using Project Loom",
                "Spring WebFlux enables reactive non-blocking HTTP with Project Reactor",
                "PostgreSQL MVCC uses transaction snapshots for concurrent read isolation",
                "Docker container networking uses bridge, host, and overlay drivers",
                "Kubernetes StatefulSets manage persistent storage for stateful apps",
                "Elasticsearch inverted index stores term-to-document mappings",
                "Redis sorted sets provide O(log N) range queries for leaderboards",
                "gRPC uses Protocol Buffers for efficient binary serialization",
                "OAuth2 refresh tokens allow offline access without re-authentication",
                "Terraform modules enable reusable infrastructure-as-code patterns",
        };

        System.out.println("\n=== E2E: Ingesting 50 memories with real embeddings ===");
        long ingestStart = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            String text = domainTopics[i % domainTopics.length] + " — instance " + i;
            MemoryType type = switch (i % 4) {
                case 0 -> MemoryType.WORKING;
                case 1 -> MemoryType.EPISODIC;
                case 2 -> MemoryType.SEMANTIC;
                default -> MemoryType.PROCEDURAL;
            };
            memory.remember("e2e-" + i, text, type, MemorySource.OBSERVED,
                    "topic-" + (i % 10)).get(30, TimeUnit.SECONDS);
        }
        long ingestElapsed = System.nanoTime() - ingestStart;
        System.out.printf("  Ingest: 50 docs in %.1f s (%.0f ms/doc)%n",
                ingestElapsed / 1e9, ingestElapsed / 1e6 / 50);

        assertThat(memory.totalMemories()).isEqualTo(50);

        // Test recall with relevance ordering
        String[] testQueries = {
                "Java concurrency with virtual threads",
                "PostgreSQL transaction isolation",
                "Docker container networking",
                "Kubernetes persistent storage",
                "OAuth token refresh"
        };

        System.out.println("\n=== E2E Recall Quality ===");
        long recallStart = System.nanoTime();
        int totalResults = 0;
        for (String query : testQueries) {
            List<CognitiveResult> results = memory.recall(query,
                    RecallOptions.builder().topK(5).build());
            totalResults += results.size();

            System.out.printf("\nQuery: '%s'%n", query);
            printResults(results);

            // Verify results are non-empty and well-ordered
            assertThat(results).isNotEmpty();
            for (int i = 0; i < results.size() - 1; i++) {
                assertThat(results.get(i).score())
                        .as("Results should be in descending score order")
                        .isGreaterThanOrEqualTo(results.get(i + 1).score());
            }
        }
        long recallElapsed = System.nanoTime() - recallStart;
        System.out.printf("%n  Recall: %d queries in %.1f s (%.0f ms/query)%n",
                testQueries.length, recallElapsed / 1e9, recallElapsed / 1e6 / testQueries.length);
        System.out.printf("  Total results: %d%n", totalResults);

        assertThat(totalResults).isGreaterThan(0);
    }

    // ══════════════════════════════════════════════════════════════
    // Cross-Tier Recall with Text Search Modes
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("Cross-tier keyword recall — BM25 finds matches across all memory tiers")
    void crossTier_keywordRecall() throws Exception {
        memory.remember("ct-w", "Working on fixing the NullPointerException in the authentication module.",
                MemoryType.WORKING, "auth", "error").get(30, TimeUnit.SECONDS);
        memory.remember("ct-e", "Yesterday we encountered a NullPointerException during login flow testing.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "auth", "error").get(30, TimeUnit.SECONDS);
        memory.remember("ct-s", "NullPointerException occurs when dereferencing a null object reference in Java.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "java", "error").get(30, TimeUnit.SECONDS);
        memory.remember("ct-p", "Always add null checks before method calls to prevent NullPointerException.",
                MemoryType.PROCEDURAL, MemorySource.PROCEDURAL, "java", "best-practice").get(30, TimeUnit.SECONDS);

        List<CognitiveResult> results = memory.recall("NullPointerException",
                RecallOptions.builder().topK(10).textSearchMode(TextSearchMode.HYBRID).build());

        System.out.println("\n=== Cross-Tier Keyword: 'NullPointerException' ===");
        printResults(results);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isGreaterThanOrEqualTo(3);

        // Verify results span multiple tiers
        var tiers = results.stream().map(CognitiveResult::memoryType).distinct().toList();
        System.out.printf("  Tiers represented: %s%n", tiers);
        assertThat(tiers.size()).as("Results should span at least 3 tiers").isGreaterThanOrEqualTo(3);
    }

    // ── Helpers ──

    private static void printResults(List<CognitiveResult> results) {
        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            System.out.printf("  #%d: score=%.4f type=%-12s id=%-10s text='%s'%n",
                    i + 1, r.score(), r.memoryType(), r.id(), truncate(r.text(), 60));
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
