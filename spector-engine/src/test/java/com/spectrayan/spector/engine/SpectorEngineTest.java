package com.spectrayan.spector.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrayan.spector.core.SimilarityFunction;
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

    private static float[] randomVector(int dim, long seed) {
        return randomVector(dim, new Random(seed));
    }

    private static float[] randomVector(int dim, Random rng) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }
}
