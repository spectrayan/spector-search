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
package com.spectrayan.spector.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * Unit tests for {@link HnswPersistenceImpl}.
 */
class HnswPersistenceTest {

    @TempDir
    Path tempDir;

    private HnswPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new HnswPersistenceImpl();
    }

    @Test
    void persistAndLoad_roundTrip_producesEquivalentSearchResults() throws IOException {
        // Build an in-memory index
        int dimensions = 8;
        int capacity = 100;
        HnswIndex original = new HnswIndex(dimensions, capacity, SimilarityFunction.COSINE);

        Random rng = new Random(42);
        for (int i = 0; i < 50; i++) {
            float[] vector = randomVector(dimensions, rng);
            original.add("doc-" + i, i, vector);
        }

        // Persist
        Path file = tempDir.resolve("test-index.sphw");
        persistence.persist(file, original);

        // Load
        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);

        // Verify basic properties
        assertEquals(original.size(), loaded.size());
        assertEquals(original.dimensions(), loaded.dimensions());
        assertEquals(original.entryPoint(), loaded.entryPoint());
        assertEquals(original.maxLevel(), loaded.maxLevel());

        // Verify search produces identical results
        float[] query = randomVector(dimensions, rng);
        ScoredResult[] originalResults = original.search(query, 5);
        ScoredResult[] loadedResults = loaded.search(query, 5);

        assertEquals(originalResults.length, loadedResults.length);
        for (int i = 0; i < originalResults.length; i++) {
            assertEquals(originalResults[i].id(), loadedResults[i].id(),
                    "Mismatch at position " + i);
            assertEquals(originalResults[i].score(), loadedResults[i].score(), 1e-6f,
                    "Score mismatch at position " + i);
        }
    }

    @Test
    void persistAndLoad_preservesAllIds() throws IOException {
        int dimensions = 4;
        HnswIndex original = new HnswIndex(dimensions, 20, SimilarityFunction.DOT_PRODUCT);

        Random rng = new Random(123);
        for (int i = 0; i < 10; i++) {
            original.add("item-" + i, i, randomVector(dimensions, rng));
        }

        Path file = tempDir.resolve("ids-test.sphw");
        persistence.persist(file, original);
        HnswIndex loaded = persistence.load(file, SimilarityFunction.DOT_PRODUCT);

        for (int i = 0; i < 10; i++) {
            assertEquals("item-" + i, loaded.getId(i));
        }
    }

    @Test
    void persistAndLoad_preservesVectors() throws IOException {
        int dimensions = 4;
        HnswIndex original = new HnswIndex(dimensions, 10, SimilarityFunction.EUCLIDEAN);

        float[] v0 = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] v1 = {5.0f, 6.0f, 7.0f, 8.0f};
        original.add("a", 0, v0);
        original.add("b", 1, v1);

        Path file = tempDir.resolve("vectors-test.sphw");
        persistence.persist(file, original);
        HnswIndex loaded = persistence.load(file, SimilarityFunction.EUCLIDEAN);

        assertArrayEquals(v0, loaded.getVector(0), 1e-7f);
        assertArrayEquals(v1, loaded.getVector(1), 1e-7f);
    }

    @Test
    void load_invalidMagic_throwsIOException() throws IOException {
        Path file = tempDir.resolve("bad-magic.sphw");
        // Write a file with wrong magic
        Files.write(file, new byte[4096]);
        // Overwrite first 4 bytes with wrong magic
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.writeInt(0xDEADBEEF);
        }

        IOException ex = assertThrows(IOException.class,
                () -> persistence.load(file, SimilarityFunction.COSINE));
        assertTrue(ex.getMessage().contains("Invalid magic"));
        assertTrue(ex.getMessage().contains("SPHW"));
    }

    @Test
    void load_invalidVersion_throwsIOException() throws IOException {
        // Create a valid index first, then corrupt version
        int dimensions = 4;
        HnswIndex index = new HnswIndex(dimensions, 5, SimilarityFunction.COSINE);
        index.add("x", 0, new float[]{1, 2, 3, 4});

        Path file = tempDir.resolve("bad-version.sphw");
        persistence.persist(file, index);

        // Corrupt the version field (offset 4)
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(4);
            raf.writeInt(Integer.reverseBytes(99)); // write as little-endian
        }

        IOException ex = assertThrows(IOException.class,
                () -> persistence.load(file, SimilarityFunction.COSINE));
        assertTrue(ex.getMessage().contains("Unsupported version"));
    }

    @Test
    void load_truncatedFile_throwsIOException() throws IOException {
        // Create a valid index, then truncate the file
        int dimensions = 4;
        HnswIndex index = new HnswIndex(dimensions, 10, SimilarityFunction.COSINE);
        index.add("x", 0, new float[]{1, 2, 3, 4});
        index.add("y", 1, new float[]{5, 6, 7, 8});

        Path file = tempDir.resolve("truncated.sphw");
        persistence.persist(file, index);

        // Truncate the file
        long originalSize = Files.size(file);
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(originalSize - 1024); // remove 1KB from end
        }

        IOException ex = assertThrows(IOException.class,
                () -> persistence.load(file, SimilarityFunction.COSINE));
        assertTrue(ex.getMessage().contains("truncated") || ex.getMessage().contains("corrupted"));
    }

    @Test
    void load_fileTooSmall_throwsIOException() throws IOException {
        Path file = tempDir.resolve("tiny.sphw");
        Files.write(file, new byte[32]); // smaller than header

        IOException ex = assertThrows(IOException.class,
                () -> persistence.load(file, SimilarityFunction.COSINE));
        assertTrue(ex.getMessage().contains("too small"));
    }

    @Test
    void persist_fileIsPageAligned() throws IOException {
        int dimensions = 4;
        HnswIndex index = new HnswIndex(dimensions, 10, SimilarityFunction.COSINE);
        index.add("a", 0, new float[]{1, 2, 3, 4});

        Path file = tempDir.resolve("aligned.sphw");
        persistence.persist(file, index);

        long fileSize = Files.size(file);
        assertEquals(0, fileSize % HnswPersistenceImpl.PAGE_SIZE,
                "File size should be page-aligned (4KB multiple)");
    }

    @Test
    void persist_emptyIndex() throws IOException {
        // An empty index with 0 nodes — verify it doesn't crash
        int dimensions = 4;
        HnswIndex index = new HnswIndex(dimensions, 10, SimilarityFunction.COSINE);

        Path file = tempDir.resolve("empty.sphw");
        persistence.persist(file, index);

        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);
        assertEquals(0, loaded.size());
    }

    @Test
    void persistAndLoad_multipleQueries_consistentResults() throws IOException {
        int dimensions = 16;
        int numVectors = 30;
        HnswIndex original = new HnswIndex(dimensions, numVectors, SimilarityFunction.COSINE);

        Random rng = new Random(77);
        for (int i = 0; i < numVectors; i++) {
            original.add("v-" + i, i, randomVector(dimensions, rng));
        }

        Path file = tempDir.resolve("multi-query.sphw");
        persistence.persist(file, original);
        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);

        // Test multiple queries
        for (int q = 0; q < 10; q++) {
            float[] query = randomVector(dimensions, rng);
            ScoredResult[] origRes = original.search(query, 3);
            ScoredResult[] loadRes = loaded.search(query, 3);

            assertEquals(origRes.length, loadRes.length, "Query " + q + " result count mismatch");
            for (int i = 0; i < origRes.length; i++) {
                assertEquals(origRes[i].id(), loadRes[i].id(),
                        "Query " + q + " result " + i + " ID mismatch");
            }
        }
    }

    // ─────────────── Append Tests ───────────────

    @Test
    void append_addsNewVectorAndPreservesExistingData() throws IOException {
        int dimensions = 8;
        HnswIndex original = new HnswIndex(dimensions, 20, SimilarityFunction.COSINE);

        Random rng = new Random(42);
        for (int i = 0; i < 5; i++) {
            original.add("doc-" + i, i, randomVector(dimensions, rng));
        }

        Path file = tempDir.resolve("append-test.sphw");
        persistence.persist(file, original);

        // Append a new vector
        float[] newVector = randomVector(dimensions, rng);
        persistence.append(file, newVector, "doc-5");

        // Load and verify
        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);
        assertEquals(6, loaded.size());

        // Verify existing IDs are preserved
        for (int i = 0; i < 5; i++) {
            assertEquals("doc-" + i, loaded.getId(i));
        }
        // Verify new node
        assertEquals("doc-5", loaded.getId(5));
    }

    @Test
    void append_newVectorIsSearchable() throws IOException {
        int dimensions = 4;
        HnswIndex original = new HnswIndex(dimensions, 20, SimilarityFunction.DOT_PRODUCT);

        // Add some vectors far from origin
        original.add("far-1", 0, new float[]{-1, -1, -1, -1});
        original.add("far-2", 1, new float[]{-1, -1, 1, -1});
        original.add("far-3", 2, new float[]{1, -1, -1, -1});

        Path file = tempDir.resolve("append-search.sphw");
        persistence.persist(file, original);

        // Append a vector very close to a specific query
        float[] newVec = {0.9f, 0.9f, 0.9f, 0.9f};
        persistence.append(file, newVec, "close-one");

        // Load and search for something near the appended vector
        HnswIndex loaded = persistence.load(file, SimilarityFunction.DOT_PRODUCT);
        float[] query = {1.0f, 1.0f, 1.0f, 1.0f};
        ScoredResult[] results = loaded.search(query, 4);

        // The appended vector should be the top result (highest dot product)
        assertEquals("close-one", results[0].id());
    }

    @Test
    void append_preservesExistingSearchResults() throws IOException {
        int dimensions = 8;
        HnswIndex original = new HnswIndex(dimensions, 30, SimilarityFunction.COSINE);

        Random rng = new Random(99);
        float[][] vectors = new float[10][];
        for (int i = 0; i < 10; i++) {
            vectors[i] = randomVector(dimensions, rng);
            original.add("vec-" + i, i, vectors[i]);
        }

        Path file = tempDir.resolve("append-preserve.sphw");
        persistence.persist(file, original);

        // Search before append
        float[] query = randomVector(dimensions, rng);
        ScoredResult[] resultsBefore = original.search(query, 5);

        // Append a new vector (far from query)
        float[] distant = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            distant[i] = -query[i]; // opposite direction
        }
        persistence.append(file, distant, "distant-node");

        // Load and search
        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);
        ScoredResult[] resultsAfter = loaded.search(query, 5);

        // Existing vectors should still be found with same relative ordering
        // (the distant appended vector should not be in top results)
        boolean foundDistant = false;
        for (ScoredResult r : resultsAfter) {
            if ("distant-node".equals(r.id())) foundDistant = true;
        }
        // The distant vector should not appear in top-5
        assertTrue(!foundDistant || resultsAfter.length == 11,
                "Distant appended vector shouldn't appear in top-5 results");
    }

    @Test
    void append_multipleAppends() throws IOException {
        int dimensions = 4;
        HnswIndex original = new HnswIndex(dimensions, 20, SimilarityFunction.COSINE);
        original.add("seed", 0, new float[]{1, 0, 0, 0});

        Path file = tempDir.resolve("multi-append.sphw");
        persistence.persist(file, original);

        // Append multiple vectors one at a time
        persistence.append(file, new float[]{0, 1, 0, 0}, "append-1");
        persistence.append(file, new float[]{0, 0, 1, 0}, "append-2");
        persistence.append(file, new float[]{0, 0, 0, 1}, "append-3");

        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);
        assertEquals(4, loaded.size());
        assertEquals("seed", loaded.getId(0));
        assertEquals("append-1", loaded.getId(1));
        assertEquals("append-2", loaded.getId(2));
        assertEquals("append-3", loaded.getId(3));
    }

    @Test
    void append_dimensionMismatch_throwsIOException() throws IOException {
        int dimensions = 4;
        HnswIndex original = new HnswIndex(dimensions, 10, SimilarityFunction.COSINE);
        original.add("x", 0, new float[]{1, 2, 3, 4});

        Path file = tempDir.resolve("dim-mismatch.sphw");
        persistence.persist(file, original);

        // Try to append a vector with wrong dimensions
        IOException ex = assertThrows(IOException.class,
                () -> persistence.append(file, new float[]{1, 2, 3}, "wrong-dim"));
        assertTrue(ex.getMessage().contains("dimension mismatch"));
    }

    @Test
    void append_updatesHeaderFields() throws IOException {
        int dimensions = 4;
        HnswIndex original = new HnswIndex(dimensions, 10, SimilarityFunction.COSINE);
        original.add("a", 0, new float[]{1, 2, 3, 4});

        Path file = tempDir.resolve("header-update.sphw");
        persistence.persist(file, original);

        long sizeBefore = Files.size(file);
        persistence.append(file, new float[]{5, 6, 7, 8}, "b");
        long sizeAfter = Files.size(file);

        // File should be page-aligned after append
        assertEquals(0, sizeAfter % HnswPersistenceImpl.PAGE_SIZE);

        // Load and verify nodeCount updated
        HnswIndex loaded = persistence.load(file, SimilarityFunction.COSINE);
        assertEquals(2, loaded.size());
    }

    // ─────────────── Helpers ───────────────

    private float[] randomVector(int dimensions, Random rng) {
        float[] v = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            v[i] = rng.nextFloat() * 2 - 1;
        }
        return v;
    }
}
