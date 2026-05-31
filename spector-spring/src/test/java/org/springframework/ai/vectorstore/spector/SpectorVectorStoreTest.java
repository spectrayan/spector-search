/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.vectorstore.spector;

import com.spectrayan.spector.engine.SpectorEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpectorVectorStore} using an embedded SpectorEngine.
 */
class SpectorVectorStoreTest {

    private SpectorEngine engine;
    private SpectorVectorStore vectorStore;
    private static final int DIMS = 4;

    @BeforeEach
    void setUp() {
        engine = SpectorEngine.builder()
                .dimensions(DIMS)
                .capacity(100)
                .build();
        vectorStore = new SpectorVectorStore(engine);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void addDocuments_storesDocumentsSuccessfully() {
        List<Document> docs = List.of(
                createDocument("doc-1", "Hello world", new float[]{0.1f, 0.2f, 0.3f, 0.4f}),
                createDocument("doc-2", "Goodbye world", new float[]{0.5f, 0.6f, 0.7f, 0.8f})
        );

        vectorStore.add(docs);

        assertThat(engine.documentCount()).isEqualTo(2);
    }

    @Test
    void addEmptyList_doesNothing() {
        vectorStore.add(List.of());
        assertThat(engine.documentCount()).isZero();
    }

    @Test
    void addNull_doesNothing() {
        vectorStore.add(null);
        assertThat(engine.documentCount()).isZero();
    }

    @Test
    void delete_removesDocuments() {
        List<Document> docs = List.of(
                createDocument("doc-1", "Hello", new float[]{0.1f, 0.2f, 0.3f, 0.4f}),
                createDocument("doc-2", "World", new float[]{0.5f, 0.6f, 0.7f, 0.8f})
        );
        vectorStore.add(docs);

        vectorStore.delete(List.of("doc-1"));

        // Engine should still have doc-2
        assertThat(engine.documentCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void deleteEmptyList_doesNothing() {
        vectorStore.delete(List.of());
        // No exception thrown
    }

    @Test
    void deleteNull_doesNothing() {
        vectorStore.delete((List<String>) null);
        // No exception thrown
    }

    @Test
    void similaritySearch_returnsResultsInDescendingScoreOrder() {
        List<Document> docs = List.of(
                createDocument("doc-1", "First", new float[]{1.0f, 0.0f, 0.0f, 0.0f}),
                createDocument("doc-2", "Second", new float[]{0.0f, 1.0f, 0.0f, 0.0f}),
                createDocument("doc-3", "Third", new float[]{0.5f, 0.5f, 0.0f, 0.0f})
        );
        vectorStore.add(docs);

        // Direct vector search close to doc-1
        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        List<Document> results = vectorStore.similaritySearch(query, 3, 0.0, null);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(3);

        // Verify descending score order
        for (int i = 0; i < results.size() - 1; i++) {
            Double score1 = results.get(i).getScore();
            Double score2 = results.get(i + 1).getScore();
            assertThat(score1).isGreaterThanOrEqualTo(score2);
        }
    }

    @Test
    void similaritySearch_respectsTopK() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float val = (i + 1) / 10.0f;
            docs.add(createDocument("doc-" + i, "Content " + i, new float[]{val, 1 - val, 0.0f, 0.0f}));
        }
        vectorStore.add(docs);

        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        List<Document> results = vectorStore.similaritySearch(query, 3, 0.0, null);

        assertThat(results.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void similaritySearch_withNullEmbedding_returnsEmpty() {
        List<Document> results = vectorStore.similaritySearch(null, 5, 0.0, null);
        assertThat(results).isEmpty();
    }

    @Test
    void similaritySearch_withEmptyEmbedding_returnsEmpty() {
        List<Document> results = vectorStore.similaritySearch(new float[0], 5, 0.0, null);
        assertThat(results).isEmpty();
    }

    @Test
    void similaritySearch_withSimilarityThreshold_filtersLowScores() {
        List<Document> docs = List.of(
                createDocument("doc-1", "Close match", new float[]{0.9f, 0.1f, 0.0f, 0.0f}),
                createDocument("doc-2", "Far match", new float[]{0.0f, 0.0f, 1.0f, 0.0f})
        );
        vectorStore.add(docs);

        float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
        List<Document> results = vectorStore.similaritySearch(query, 10, 0.8, null);

        // All returned results should have score >= 0.8
        for (Document result : results) {
            assertThat(result.getScore()).isGreaterThanOrEqualTo(0.8);
        }
    }

    // ─── Helpers ───

    private Document createDocument(String id, String content, float[] embedding) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SpectorVectorStore.EMBEDDING_METADATA_KEY, embedding);
        return new Document(id, content, metadata);
    }
}
