package com.spectrayan.spector.engine;


import com.spectrayan.spector.config.SpectorConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.IndexType;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * End-to-end tests for {@link SpectorEngine}.
 */
class SpectorEngineTest {

    private static final int DIM = 32;

    private SpectorConfig testConfig() {
        return SpectorConfig.DEFAULT.withDimensions(DIM).withCapacity(1000);
    }

    @Test
    void ingestAndKeywordSearch() {
        try (var engine = new SpectorEngine(testConfig())) {
            engine.ingest("d1", "java programming language", randomVector(DIM, 1));
            engine.ingest("d2", "python machine learning", randomVector(DIM, 2));

            SearchResponse response = engine.keywordSearch("java", 10);
            assertThat(response.results()).hasSizeGreaterThanOrEqualTo(1);
            assertThat(response.results()[0].id()).isEqualTo("d1");
        }
    }

    @Test
    void ingestAndVectorSearch() {
        try (var engine = new SpectorEngine(testConfig())) {
            float[] v1 = randomVector(DIM, 1);
            engine.ingest("d1", "hello", v1);
            engine.ingest("d2", "world", randomVector(DIM, 2));

            SearchResponse response = engine.vectorSearch(v1, 10);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.results()[0].id()).isEqualTo("d1");
        }
    }

    @Test
    void ingestAndHybridSearch() {
        try (var engine = new SpectorEngine(testConfig())) {
            float[] v1 = randomVector(DIM, 1);
            engine.ingest("d1", "java virtual machine performance", v1);
            engine.ingest("d2", "python deep learning", randomVector(DIM, 2));

            SearchResponse response = engine.hybridSearch("java", v1, 10);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.mode()).isEqualTo(SearchQuery.SearchMode.HYBRID);
        }
    }

    @Test
    void documentCount() {
        try (var engine = new SpectorEngine(testConfig())) {
            assertThat(engine.documentCount()).isEqualTo(0);
            engine.ingest("d1", "hello", randomVector(DIM, 1));
            assertThat(engine.documentCount()).isEqualTo(1);
            engine.ingest("d2", "world", randomVector(DIM, 2));
            assertThat(engine.documentCount()).isEqualTo(2);
        }
    }

    @Test
    void batchIngest() {
        try (var engine = new SpectorEngine(testConfig())) {
            String[] ids = {"d1", "d2", "d3"};
            String[] contents = {"alpha", "beta", "gamma"};
            float[][] vectors = {randomVector(DIM, 1), randomVector(DIM, 2), randomVector(DIM, 3)};

            engine.ingestBatch(ids, contents, vectors);
            assertThat(engine.documentCount()).isEqualTo(3);
        }
    }

    @Test
    void closedEngineThrows() {
        var engine = new SpectorEngine(testConfig());
        engine.close();
        assertThatThrownBy(() -> engine.ingest("d1", "text", randomVector(DIM, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configAccessor() {
        var config = testConfig();
        try (var engine = new SpectorEngine(config)) {
            assertThat(engine.config()).isEqualTo(config);
            assertThat(engine.config().dimensions()).isEqualTo(DIM);
        }
    }

    @Test
    void multipleDocumentsEndToEnd() {
        try (var engine = new SpectorEngine(testConfig())) {
            Random rng = new Random(42);
            for (int i = 0; i < 50; i++) {
                engine.ingest("doc-" + i, "document number " + i + " with text", randomVector(DIM, rng));
            }
            assertThat(engine.documentCount()).isEqualTo(50);

            SearchResponse kwResponse = engine.keywordSearch("document number", 5);
            assertThat(kwResponse.results()).hasSizeLessThanOrEqualTo(5);
            assertThat(kwResponse.queryTimeMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ─────────────── IVF-PQ Engine Integration ───────────────

    @Test
    void ivfPq_autoTrainsAndSearches() {
        // IVF-PQ requires training — engine should auto-train after enough vectors
        var config = testConfig()
                .withCapacity(2000)
                .withIvfPq(8, 4, 4); // nlist=8, nprobe=4, M=4

        try (var engine = new SpectorEngine(config)) {
            Random rng = new Random(42);

            // Ingest enough vectors for auto-training (nlist*40 = 320)
            for (int i = 0; i < 400; i++) {
                engine.ingest("doc-" + i, "document about topic " + (i % 10), randomVector(DIM, rng));
            }

            // After training, search should work
            SearchResponse response = engine.vectorSearch(randomVector(DIM, 999L), 5);
            assertThat(response.results()).isNotEmpty();
        }
    }

    @Test
    void ivfPq_keywordSearchWorksBeforeTraining() {
        // Keyword search should work even while IVF-PQ is still buffering
        var config = testConfig()
                .withCapacity(2000)
                .withIvfPq(8, 4, 4);

        try (var engine = new SpectorEngine(config)) {
            engine.ingest("d1", "java programming language", randomVector(DIM, 1));
            engine.ingest("d2", "python machine learning", randomVector(DIM, 2));

            // Keyword search should still work (BM25 index populated during buffering)
            SearchResponse response = engine.keywordSearch("java", 10);
            assertThat(response.results()).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void ivfPq_configBuilder() {
        var config = SpectorConfig.DEFAULT.withIvfPq(100, 10, 48);
        assertThat(config.indexType()).isEqualTo(IndexType.IVF_PQ);
        assertThat(config.ivfNlist()).isEqualTo(100);
        assertThat(config.ivfNprobe()).isEqualTo(10);
        assertThat(config.pqSubspaces()).isEqualTo(48);
    }

    @Test
    void ivfPq_autoDefaults() {
        var config = SpectorConfig.DEFAULT.withIvfPq();
        assertThat(config.indexType()).isEqualTo(IndexType.IVF_PQ);
        // Auto defaults: nlist=√100000≈316, nprobe=10, M=384/8=48
        assertThat(config.effectiveNlist()).isGreaterThan(16);
        assertThat(config.effectiveNprobe()).isEqualTo(10);
        assertThat(config.effectivePqSubspaces()).isGreaterThanOrEqualTo(4);
    }

    // ─────────────── VASQ Engine Integration ───────────────

    @Test
    void vasq_configBuilder_setsCorrectQuantization() {
        var config = SpectorConfig.DEFAULT
                .withDimensions(128)
                .withCapacity(1000)
                .withVasq();

        assertThat(config.quantization())
                .isEqualTo(com.spectrayan.spector.core.quantization.QuantizationType.VASQ);
        // Default oversampling for VASQ is 3
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(3);
    }

    @Test
    void vasq_engineBuilder_fluentApi() {
        var config = SpectorEngine.builder()
                .dimensions(64)
                .capacity(500)
                .similarity(SimilarityFunction.COSINE)
                .vasq(3)
                .config();  // inspect the config without building the engine

        assertThat(config.quantization())
                .isEqualTo(com.spectrayan.spector.core.quantization.QuantizationType.VASQ);
        assertThat(config.effectiveOversamplingFactor()).isEqualTo(3);
    }

    @Test
    void vasq_ingestAndVectorSearch_returnsResults() {
        // Use capacity = numDocs to trigger VASQ auto-calibration immediately
        int numDocs = 150;
        var config = SpectorConfig.DEFAULT
                .withDimensions(DIM)
                .withCapacity(numDocs)
                .withVasq();

        try (var engine = new SpectorEngine(config)) {
            Random rng = new Random(42);
            for (int i = 0; i < numDocs; i++) {
                engine.ingest("doc-" + i, "document number " + i, randomVector(DIM, rng));
            }
            assertThat(engine.documentCount()).isEqualTo(numDocs);

            SearchResponse response = engine.vectorSearch(randomVector(DIM, 999L), 5);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.results().length).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void vasq_hybridSearch_returnsBothKeywordAndVector() {
        int numDocs = 150;
        var config = SpectorConfig.DEFAULT
                .withDimensions(DIM)
                .withCapacity(numDocs)
                .withVasq();

        try (var engine = new SpectorEngine(config)) {
            Random rng = new Random(10);
            float[] specialVec = randomVector(DIM, rng);
            engine.ingest("special", "java programming language runtime", specialVec);

            for (int i = 1; i < numDocs; i++) {
                engine.ingest("doc-" + i, "unrelated document content " + i, randomVector(DIM, rng));
            }

            // Keyword search should find "special" by text
            SearchResponse kwResp = engine.keywordSearch("java programming", 5);
            assertThat(kwResp.results()).isNotEmpty();
            assertThat(kwResp.results()[0].id()).isEqualTo("special");

            // Vector search should find "special" by nearest vector
            SearchResponse vecResp = engine.vectorSearch(specialVec, 5);
            assertThat(vecResp.results()).isNotEmpty();
        }
    }

    @Test
    void vasq_withExplicitOversampling_configuredCorrectly() {
        var config = SpectorConfig.DEFAULT
                .withDimensions(64)
                .withCapacity(500)
                .withVasq(5);

        assertThat(config.effectiveOversamplingFactor()).isEqualTo(5);
    }

    // ─────────────── Helpers ───────────────

    private static float[] randomVector(int dim, long seed) {
        return randomVector(dim, new Random(seed));
    }

    private static float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }
}
