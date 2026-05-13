package com.spectrayan.spector.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrayan.spector.core.SimilarityFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Extended tests for {@link HnswIndex} — edge cases, large datasets,
 * structured content search.
 */
class HnswIndexExtendedTest {

    // ─────────────── Multi-dimensional recall ───────────────

    @ParameterizedTest
    @EnumSource(SimilarityFunction.class)
    void recallAcrossAllSimilarityFunctions(SimilarityFunction sim) {
        int n = 300, k = 10, dim = 64;
        var params = new HnswParams(16, 200, 100);

        try (var idx = new HnswIndex(dim, n, sim, params)) {
            float[][] allVectors = new float[n][];
            Random rng = new Random(42);

            for (int i = 0; i < n; i++) {
                allVectors[i] = randomVector(dim, rng);
                idx.add("doc-" + i, i, allVectors[i]);
            }

            float[] query = randomVector(dim, new Random(999));
            Set<String> trueTopK = bruteForceTopK(allVectors, query, k, sim);

            ScoredResult[] results = idx.search(query, k);
            Set<String> hnswTopK = new HashSet<>();
            for (var r : results) hnswTopK.add(r.id());

            int hits = 0;
            for (String id : trueTopK) if (hnswTopK.contains(id)) hits++;
            float recall = (float) hits / k;

            assertThat(recall).as("Recall@%d for %s should be >= 0.7", k, sim)
                    .isGreaterThanOrEqualTo(0.7f);
        }
    }

    // ─────────────── High-dimensional vectors ───────────────

    @Test
    void highDimensionalVectors() {
        int dim = 384; // typical embedding dim
        int n = 100;
        try (var idx = new HnswIndex(dim, n, SimilarityFunction.COSINE)) {
            Random rng = new Random(42);
            for (int i = 0; i < n; i++) {
                idx.add("doc-" + i, i, randomVector(dim, rng));
            }
            assertThat(idx.size()).isEqualTo(n);

            ScoredResult[] results = idx.search(randomVector(dim, new Random(99)), 10);
            assertThat(results).hasSize(10);
        }
    }

    // ─────────────── Small vectors (2-dim) ───────────────

    @Test
    void twoDimensionalVectors() {
        try (var idx = new HnswIndex(2, 10, SimilarityFunction.EUCLIDEAN)) {
            idx.add("origin", 0, new float[]{0, 0});
            idx.add("near", 1, new float[]{0.1f, 0.1f});
            idx.add("far", 2, new float[]{10, 10});

            ScoredResult[] results = idx.search(new float[]{0, 0}, 3);
            assertThat(results[0].id()).isEqualTo("origin"); // exact match
            assertThat(results[1].id()).isEqualTo("near");
        }
    }

    // ─────────────── Identical vectors ───────────────

    @Test
    void identicalVectorsHandled() {
        float[] v = {1, 0, 0, 0};
        try (var idx = new HnswIndex(4, 10, SimilarityFunction.COSINE)) {
            idx.add("a", 0, v);
            idx.add("b", 1, v);
            idx.add("c", 2, v);

            ScoredResult[] results = idx.search(v, 3);
            assertThat(results).hasSize(3);
            // All should have perfect cosine score
            for (var r : results) {
                assertThat(r.score()).isGreaterThan(0.99f);
            }
        }
    }

    // ─────────────── Search with k > n ───────────────

    @Test
    void searchReturnsAllWhenKExceedsSize() {
        try (var idx = new HnswIndex(3, 10, SimilarityFunction.COSINE)) {
            idx.add("a", 0, new float[]{1, 0, 0});
            idx.add("b", 1, new float[]{0, 1, 0});

            ScoredResult[] results = idx.search(new float[]{1, 0, 0}, 100);
            assertThat(results).hasSize(2); // only 2 docs in index
        }
    }

    // ─────────────── Structured content with BM25 ───────────────

    @Test
    void searchXmlContent() {
        var bm25 = new BM25Index();
        String xml1 = "<doc><title>Java Vector API</title><body>SIMD accelerated search</body></doc>";
        String xml2 = "<doc><title>Python NumPy</title><body>numerical computing</body></doc>";

        bm25.index("d1", ContentExtractor.fromXml(xml1));
        bm25.index("d2", ContentExtractor.fromXml(xml2));

        ScoredResult[] results = bm25.search("SIMD search", 10);
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results[0].id()).isEqualTo("d1");
        bm25.close();
    }

    @Test
    void searchJsonContent() {
        var bm25 = new BM25Index();
        String json1 = """
                {"title": "HNSW Algorithm", "tags": ["graph", "nearest neighbor"]}
                """;
        String json2 = """
                {"title": "B-Tree Index", "tags": ["database", "sorted"]}
                """;

        bm25.index("d1", ContentExtractor.fromJson(json1));
        bm25.index("d2", ContentExtractor.fromJson(json2));

        ScoredResult[] results = bm25.search("nearest neighbor", 10);
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results[0].id()).isEqualTo("d1");
        bm25.close();
    }

    @Test
    void searchJavaObjectContent() {
        var bm25 = new BM25Index();
        String obj1 = "Product{name=Spector Search Engine, category=Software, price=0.0}";
        String obj2 = "Product{name=Office Chair, category=Furniture, price=299.99}";

        bm25.index("d1", ContentExtractor.fromJavaObject(obj1));
        bm25.index("d2", ContentExtractor.fromJavaObject(obj2));

        ScoredResult[] results = bm25.search("search engine", 10);
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results[0].id()).isEqualTo("d1");
        bm25.close();
    }

    @Test
    void searchAutoDetectedContent() {
        var bm25 = new BM25Index();
        bm25.index("xml", ContentExtractor.extract("<doc><text>vector similarity</text></doc>"));
        bm25.index("json", ContentExtractor.extract("{\"text\": \"keyword search\"}"));
        bm25.index("plain", ContentExtractor.extract("hybrid fusion search"));

        assertThat(bm25.search("vector", 10)[0].id()).isEqualTo("xml");
        assertThat(bm25.search("keyword", 10)[0].id()).isEqualTo("json");
        assertThat(bm25.search("fusion", 10)[0].id()).isEqualTo("plain");
        bm25.close();
    }

    // ─────────────── Helpers ───────────────

    private static Set<String> bruteForceTopK(float[][] vectors, float[] query, int k, SimilarityFunction sim) {
        record Pair(String id, float score) {}
        Pair[] pairs = new Pair[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            pairs[i] = new Pair("doc-" + i, sim.compute(query, vectors[i]));
        }
        if (sim.higherIsBetter()) {
            java.util.Arrays.sort(pairs, (a, b) -> Float.compare(b.score, a.score));
        } else {
            java.util.Arrays.sort(pairs, (a, b) -> Float.compare(a.score, b.score));
        }
        Set<String> topK = new HashSet<>();
        for (int i = 0; i < k && i < pairs.length; i++) topK.add(pairs[i].id);
        return topK;
    }

    private static float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }
}
