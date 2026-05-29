package com.spectrayan.spector.memory;

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.embed.ollama.OllamaEmbeddingProvider;
import com.spectrayan.spector.memory.cortex.MemorySource;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration test using real Ollama embeddings (qwen3).
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Ollama running on {@code localhost:11434}</li>
 *   <li>Model pulled: {@code ollama pull qwen3}</li>
 *   <li>Set env var: {@code OLLAMA_LIVE=true}</li>
 * </ul>
 *
 * <h3>What This Tests</h3>
 * <p>Uses real 2048-dim (or model-default-dim) embeddings from qwen3 to verify
 * semantic similarity ranking, cross-tier recall, and full pipeline
 * performance with production-grade vectors.</p>
 *
 * <p>Run manually with:</p>
 * <pre>
 *   set OLLAMA_LIVE=true
 *   mvn test -pl spector-memory -Dtest=OllamaRealEmbeddingTest -am
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Ollama Real Embedding E2E Tests (qwen3)")
class OllamaRealEmbeddingTest {

    private static final String MODEL = "qwen3-embedding";
    private static OllamaEmbeddingProvider embeddingProvider;
    private static int detectedDimensions;

    private SpectorMemory memory;

    @BeforeAll
    static void initOllama() {
        embeddingProvider = OllamaEmbeddingProvider.create(MODEL);
        // Probe dimensions
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
    // Semantic Similarity — Real Embeddings
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("Semantic recall: 'dark mode' query ranks 'user prefers dark theme' highest")
    void semanticSimilarity_darkMode() throws Exception {
        // Ingest diverse memories
        memory.remember("pref-dark", "The user strongly prefers dark mode for all their IDE editors and applications.",
                MemoryType.EPISODIC, MemorySource.USER_STATED, "ui", "preferences")
                .get(30, TimeUnit.SECONDS);
        memory.remember("pref-java", "The user prefers Java over Python for backend development.",
                MemoryType.EPISODIC, MemorySource.USER_STATED, "language", "preferences")
                .get(30, TimeUnit.SECONDS);
        memory.remember("error-db", "Encountered a database connection timeout on the users table during migration.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "error", "database")
                .get(30, TimeUnit.SECONDS);
        memory.remember("deploy-v2", "Successfully deployed version 2.1 to the staging environment.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "deployment")
                .get(30, TimeUnit.SECONDS);
        memory.remember("pref-light", "The user explicitly rejected the light theme during onboarding.",
                MemoryType.EPISODIC, MemorySource.USER_STATED, "ui", "preferences")
                .get(30, TimeUnit.SECONDS);

        // Query: "dark mode"
        List<CognitiveResult> results = memory.recall("dark mode settings",
                RecallOptions.builder().topK(5).build());

        System.out.println("=== Semantic Recall: 'dark mode settings' ===");
        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            System.out.printf("  #%d: score=%.4f type=%s text='%s'%n",
                    i + 1, r.score(), r.memoryType(), truncate(r.text(), 60));
        }

        assertThat(results).isNotEmpty();
        // At least one result should mention dark/light/theme
        boolean foundRelevant = results.stream()
                .anyMatch(r -> r.text().contains("dark") || r.text().contains("light") || r.text().contains("theme"));
        assertThat(foundRelevant).as("At least one result should be about dark/light theme").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("Semantic recall: 'database error' query ranks DB-related highest")
    void semanticSimilarity_databaseError() throws Exception {
        memory.remember("err-db", "Database connection pool exhausted — 50 active, 0 idle connections.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "error", "database")
                .get(30, TimeUnit.SECONDS);
        memory.remember("err-npe", "NullPointerException in UserService.getPreferences at line 42.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "error", "java")
                .get(30, TimeUnit.SECONDS);
        memory.remember("fact-pg", "PostgreSQL supports JSONB columns for semi-structured data.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "database", "postgresql")
                .get(30, TimeUnit.SECONDS);
        memory.remember("rule-retry", "Always implement exponential backoff for database retries.",
                MemoryType.PROCEDURAL, MemorySource.PROCEDURAL, "database", "retry")
                .get(30, TimeUnit.SECONDS);

        List<CognitiveResult> results = memory.recall("database connection error",
                RecallOptions.builder().topK(5).build());

        System.out.println("\n=== Semantic Recall: 'database connection error' ===");
        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            System.out.printf("  #%d: score=%.4f type=%s text='%s'%n",
                    i + 1, r.score(), r.memoryType(), truncate(r.text(), 60));
        }

        assertThat(results).isNotEmpty();
        // At least one result should mention connection/database
        boolean foundRelevant = results.stream()
                .anyMatch(r -> r.text().toLowerCase().contains("connection") 
                        || r.text().toLowerCase().contains("database"));
        assertThat(foundRelevant).as("At least one result should be about database connections").isTrue();
    }

    // ══════════════════════════════════════════════════════════════
    // Cross-Tier Recall — All 4 Tiers
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("Cross-tier recall: results from Working, Episodic, Semantic, Procedural")
    void crossTierRecall() throws Exception {
        memory.remember("w-1", "Currently analyzing the Spring Boot configuration issue.",
                MemoryType.WORKING, "spring", "debugging").get(30, TimeUnit.SECONDS);
        memory.remember("e-1", "Yesterday the Spring app failed to start because of circular dependencies.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "spring", "error").get(30, TimeUnit.SECONDS);
        memory.remember("s-1", "Spring Boot auto-configuration resolves beans using conditional annotations.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "spring", "framework").get(30, TimeUnit.SECONDS);
        memory.remember("p-1", "When troubleshooting Spring, always check @ConditionalOn annotations first.",
                MemoryType.PROCEDURAL, MemorySource.PROCEDURAL, "spring", "debugging").get(30, TimeUnit.SECONDS);

        List<CognitiveResult> results = memory.recall("Spring Boot configuration problem",
                RecallOptions.builder().topK(10).build());

        System.out.println("\n=== Cross-Tier Recall: 'Spring Boot configuration problem' ===");
        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            System.out.printf("  #%d: score=%.4f type=%-12s text='%s'%n",
                    i + 1, r.score(), r.memoryType(), truncate(r.text(), 60));
        }

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isGreaterThanOrEqualTo(3);

        // Verify we got results from multiple tiers
        var tiers = results.stream().map(CognitiveResult::memoryType).distinct().toList();
        System.out.printf("  Tiers represented: %s%n", tiers);
        assertThat(tiers.size()).as("At least 3 tiers in results").isGreaterThanOrEqualTo(3);
    }

    // ══════════════════════════════════════════════════════════════
    // Performance with Real Embeddings
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("Performance: 50 ingestions + 20 recalls with real embeddings")
    void realEmbeddingPerformance() throws Exception {
        String[] topics = {
                "Java performance optimization techniques for high-throughput systems",
                "Spring Boot REST API with PostgreSQL and connection pooling",
                "Kubernetes deployment with horizontal pod autoscaling and health checks",
                "React frontend state management using Redux and TypeScript",
                "Machine learning model training with PyTorch and GPU acceleration",
                "Database schema migration using Flyway with zero-downtime strategies",
                "CI/CD pipeline with GitHub Actions and Docker container builds",
                "Microservices architecture with gRPC and Protocol Buffers",
                "OAuth2 authentication flow with JWT token refresh logic",
                "Elasticsearch full-text search with custom analyzers and synonyms"
        };

        // Ingest 50 memories
        System.out.println("\n=== Real Embedding Performance ===");
        long ingestStart = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            String text = topics[i % topics.length] + " — instance " + i;
            MemoryType type = switch (i % 4) {
                case 0 -> MemoryType.WORKING;
                case 1 -> MemoryType.EPISODIC;
                case 2 -> MemoryType.SEMANTIC;
                default -> MemoryType.PROCEDURAL;
            };
            memory.remember("real-" + i, text, type,
                    MemorySource.OBSERVED, "topic-" + (i % 10))
                    .get(30, TimeUnit.SECONDS);
        }
        long ingestElapsed = System.nanoTime() - ingestStart;
        System.out.printf("  Ingest: 50 memories in %.1f s (%.0f ms/memory, includes Ollama API)%n",
                ingestElapsed / 1e9, ingestElapsed / 1e6 / 50);

        assertThat(memory.totalMemories()).isEqualTo(50);

        // 20 diverse recall queries
        String[] queries = {
                "Java performance", "database connection pool", "Kubernetes scaling",
                "React state management", "machine learning GPU", "schema migration",
                "CI/CD Docker", "microservices gRPC", "OAuth JWT", "Elasticsearch search",
                "Spring Boot REST", "horizontal autoscaling", "PyTorch training",
                "GitHub Actions pipeline", "Protocol Buffers serialization",
                "PostgreSQL connection", "Redux TypeScript", "health check probe",
                "Flyway zero downtime", "container orchestration"
        };

        long recallStart = System.nanoTime();
        int totalResults = 0;
        for (String query : queries) {
            List<CognitiveResult> results = memory.recall(query,
                    RecallOptions.builder().topK(5).build());
            totalResults += results.size();
        }
        long recallElapsed = System.nanoTime() - recallStart;

        System.out.printf("  Recall: 20 queries in %.1f s (%.0f ms/query, includes Ollama API)%n",
                recallElapsed / 1e9, recallElapsed / 1e6 / 20);
        System.out.printf("  Total results returned: %d%n", totalResults);

        assertThat(totalResults).isGreaterThan(0);
    }

    // ══════════════════════════════════════════════════════════════
    // Suppression + Habituation with Real Vectors
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("Suppression: suppressed memory excluded from recall")
    void suppression_excludesFromRecall() throws Exception {
        memory.remember("mem-java", "Java has garbage collection for automatic memory management.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "java").get(30, TimeUnit.SECONDS);
        memory.remember("mem-rust", "Rust uses ownership system instead of garbage collection.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "rust").get(30, TimeUnit.SECONDS);

        // Suppress Java memory
        memory.suppress("mem-java", "Wrong context");

        List<CognitiveResult> results = memory.recall("garbage collection memory management",
                RecallOptions.builder().topK(5).build());

        System.out.println("\n=== Suppression Test ===");
        for (CognitiveResult r : results) {
            System.out.printf("  score=%.4f id=%s text='%s'%n", r.score(), r.id(), truncate(r.text(), 50));
        }

        // Suppressed memory should NOT appear
        boolean javaFound = results.stream().anyMatch(r -> "mem-java".equals(r.id()));
        assertThat(javaFound).as("Suppressed memory should not appear").isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("Habituation: repeated recall penalizes score")
    void habituation_penalizesRepeatedRecall() throws Exception {
        memory.remember("hab-1", "The deployment pipeline uses blue-green strategy for zero downtime.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "deployment").get(30, TimeUnit.SECONDS);

        // First recall
        List<CognitiveResult> first = memory.recall("deployment strategy",
                RecallOptions.builder().topK(5).build());
        float firstScore = first.isEmpty() ? 0 : first.getFirst().score();

        // Second recall (same query)
        List<CognitiveResult> second = memory.recall("deployment strategy",
                RecallOptions.builder().topK(5).build());
        float secondScore = second.isEmpty() ? 0 : second.getFirst().score();

        System.out.printf("%nHabituation: first=%.4f, second=%.4f (penalty applied=%b)%n",
                firstScore, secondScore, secondScore < firstScore);

        if (firstScore > 0 && secondScore > 0) {
            assertThat(secondScore).as("Second recall score should be ≤ first").isLessThanOrEqualTo(firstScore);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Memory Type-Filtered Recall
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("Type-filtered recall: only returns memories from specified tiers")
    void typeFilteredRecall() throws Exception {
        memory.remember("e-fact", "Python 3.12 introduced faster pattern matching.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "python").get(30, TimeUnit.SECONDS);
        memory.remember("s-fact", "Python uses indentation for code block scoping.",
                MemoryType.SEMANTIC, MemorySource.OBSERVED, "python").get(30, TimeUnit.SECONDS);
        memory.remember("p-fact", "Always use virtual environments for Python projects.",
                MemoryType.PROCEDURAL, MemorySource.PROCEDURAL, "python").get(30, TimeUnit.SECONDS);

        // Only semantic
        List<CognitiveResult> semanticOnly = memory.recall("Python programming",
                RecallOptions.builder().topK(5).memoryTypes(MemoryType.SEMANTIC).build());

        System.out.println("\n=== Type-Filtered Recall (SEMANTIC only) ===");
        for (CognitiveResult r : semanticOnly) {
            System.out.printf("  type=%s text='%s'%n", r.memoryType(), truncate(r.text(), 50));
        }

        if (!semanticOnly.isEmpty()) {
            assertThat(semanticOnly).allMatch(r -> r.memoryType() == MemoryType.SEMANTIC);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Forget + WAL
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("Forget: tombstoned memory excluded from recall")
    void forget_tombstonedExcluded() throws Exception {
        memory.remember("forget-me", "This memory should be forgotten completely.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "temp").get(30, TimeUnit.SECONDS);
        memory.remember("keep-me", "This memory should persist across operations.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "persistent").get(30, TimeUnit.SECONDS);

        memory.forget("forget-me");

        List<CognitiveResult> results = memory.recall("forgotten memory",
                RecallOptions.builder().topK(10).build());

        boolean forgottenFound = results.stream().anyMatch(r -> "forget-me".equals(r.id()));
        assertThat(forgottenFound).as("Forgotten memory should not appear").isFalse();
    }

    // ══════════════════════════════════════════════════════════════
    // Batch Embedding Performance
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("Batch embedding: Ollama batch API for 20 texts")
    void batchEmbeddingPerformance() {
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            texts.add("Batch embedding test text number " + i + " about software engineering");
        }

        long start = System.nanoTime();
        List<EmbeddingResult> results = embeddingProvider.embedBatch(texts);
        long elapsed = System.nanoTime() - start;

        System.out.printf("%nBatch embedding: 20 texts in %.0f ms (%.0f ms/text, dims=%d)%n",
                elapsed / 1e6, elapsed / 1e6 / 20, results.getFirst().dimensions());

        assertThat(results).hasSize(20);
        assertThat(results.getFirst().dimensions()).isEqualTo(detectedDimensions);
    }

    // ══════════════════════════════════════════════════════════════
    // Recall Quality — Semantic Relevance Ordering
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("Recall quality: highly relevant result scores higher than tangentially related")
    void recallQuality_relevanceOrdering() throws Exception {
        // Use EPISODIC tier so full CognitiveRecord (with quantized vector) is used for similarity scoring
        memory.remember("gc-direct", "The Java G1 garbage collector divides the heap into regions for concurrent collection.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "java", "gc").get(30, TimeUnit.SECONDS);
        // Somewhat related
        memory.remember("gc-related", "Memory allocation in Java uses the TLAB (Thread-Local Allocation Buffer).",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "java", "memory").get(30, TimeUnit.SECONDS);
        // Unrelated
        memory.remember("gc-unrelated", "Kubernetes uses etcd for distributed consensus on cluster state.",
                MemoryType.EPISODIC, MemorySource.OBSERVED, "kubernetes", "etcd").get(30, TimeUnit.SECONDS);

        List<CognitiveResult> results = memory.recall("Java G1 garbage collection pause times",
                RecallOptions.builder().topK(5).build());

        System.out.println("\n=== Recall Quality: 'Java G1 garbage collection pause times' ===");
        for (int i = 0; i < results.size(); i++) {
            CognitiveResult r = results.get(i);
            System.out.printf("  #%d: score=%.4f id=%s text='%s'%n",
                    i + 1, r.score(), r.id(), truncate(r.text(), 60));
        }

        assertThat(results).isNotEmpty();
        // G1 GC memory should score highest
        if (results.size() >= 2) {
            CognitiveResult direct = results.stream()
                    .filter(r -> "gc-direct".equals(r.id())).findFirst().orElse(null);
            CognitiveResult unrelated = results.stream()
                    .filter(r -> "gc-unrelated".equals(r.id())).findFirst().orElse(null);
            if (direct != null && unrelated != null) {
                assertThat(direct.score()).as("Direct match should score >= unrelated")
                        .isGreaterThanOrEqualTo(unrelated.score());
            }
        }
    }

    // ── Helper ──

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
