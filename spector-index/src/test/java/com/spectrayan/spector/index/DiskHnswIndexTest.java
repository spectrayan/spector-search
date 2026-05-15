package com.spectrayan.spector.index;

import com.spectrayan.spector.core.SimilarityFunction;
import com.spectrayan.spector.storage.IndexFileFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for disk-based HNSW: {@link DiskHnswWriter} and {@link DiskHnswIndex}.
 */
class DiskHnswIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndRead_roundTrip() throws IOException {
        int dims = 32;
        int numDocs = 100;
        var inMemory = new HnswIndex(dims, numDocs + 10, SimilarityFunction.COSINE);

        java.util.Random rng = new java.util.Random(42);
        float[][] vectors = new float[numDocs][dims];
        for (int i = 0; i < numDocs; i++) {
            vectors[i] = randomVector(rng, dims);
            inMemory.add("doc-" + i, i, vectors[i]);
        }

        // Write to disk
        Path indexFile = tempDir.resolve("test-index.spct");
        DiskHnswWriter.write(inMemory, indexFile);
        assertTrue(java.nio.file.Files.exists(indexFile));
        assertTrue(java.nio.file.Files.size(indexFile) > IndexFileFormat.HEADER_SIZE);

        // Read back
        try (var diskIndex = DiskHnswIndex.open(indexFile)) {
            assertEquals(numDocs, diskIndex.size());
            assertEquals(SimilarityFunction.COSINE, diskIndex.similarityFunction());

            // Search should work
            float[] query = randomVector(rng, dims);
            ScoredResult[] results = diskIndex.search(query, 5);
            assertNotNull(results);
            assertTrue(results.length > 0, "Disk index should return search results");
            assertTrue(results.length <= 5);
        }
    }

    @Test
    void searchQuality_matchesInMemory() throws IOException {
        int dims = 64;
        int numDocs = 500;
        var inMemory = new HnswIndex(dims, numDocs + 10, SimilarityFunction.COSINE);

        java.util.Random rng = new java.util.Random(99);
        for (int i = 0; i < numDocs; i++) {
            inMemory.add("doc-" + i, i, randomVector(rng, dims));
        }

        Path indexFile = tempDir.resolve("quality-test.spct");
        DiskHnswWriter.write(inMemory, indexFile);

        try (var diskIndex = DiskHnswIndex.open(indexFile)) {
            int k = 10;
            int queryCount = 10;
            int totalOverlap = 0;

            rng = new java.util.Random(999);
            for (int q = 0; q < queryCount; q++) {
                float[] query = randomVector(rng, dims);
                ScoredResult[] memResults = inMemory.search(query, k);
                ScoredResult[] diskResults = diskIndex.search(query, k);

                java.util.Set<String> memIds = new java.util.HashSet<>();
                for (ScoredResult r : memResults) memIds.add(r.id());
                for (ScoredResult r : diskResults) {
                    if (memIds.contains(r.id())) totalOverlap++;
                }
            }

            double overlap = (double) totalOverlap / (queryCount * k);
            assertTrue(overlap >= 0.7,
                    "Disk index results should overlap >= 70% with in-memory, got " + overlap);
        }
    }

    @Test
    void headerFormat_readWrite() {
        var header = new IndexFileFormat.Header(
                IndexFileFormat.MAGIC, IndexFileFormat.VERSION,
                128, 10000, 16, 32, 42, 3,
                SimilarityFunction.COSINE.ordinal(), 0,
                4096, 50000, 100000, 264, 150000);

        // Allocate a buffer and write/read
        byte[] buffer = new byte[IndexFileFormat.HEADER_SIZE];
        var segment = java.lang.foreign.MemorySegment.ofArray(buffer);

        IndexFileFormat.writeHeader(segment, header);
        var read = IndexFileFormat.readHeader(segment);

        assertEquals(header.magic(), read.magic());
        assertEquals(header.version(), read.version());
        assertEquals(header.dimensions(), read.dimensions());
        assertEquals(header.nodeCount(), read.nodeCount());
        assertEquals(header.m(), read.m());
        assertEquals(header.entryPoint(), read.entryPoint());
        assertEquals(header.maxLevel(), read.maxLevel());
        assertEquals(header.vectorDataOffset(), read.vectorDataOffset());
        assertEquals(header.graphDataOffset(), read.graphDataOffset());
        assertEquals(header.graphBlockSize(), read.graphBlockSize());
    }

    @Test
    void diskIndex_isReadOnly() throws IOException {
        int dims = 16;
        var inMemory = new HnswIndex(dims, 10, SimilarityFunction.COSINE);
        inMemory.add("doc-0", 0, randomVector(new java.util.Random(1), dims));

        Path indexFile = tempDir.resolve("readonly.spct");
        DiskHnswWriter.write(inMemory, indexFile);

        try (var diskIndex = DiskHnswIndex.open(indexFile)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> diskIndex.add("new-doc", 1, new float[dims]));
        }
    }

    private float[] randomVector(java.util.Random rng, int dims) {
        float[] v = new float[dims];
        float norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = rng.nextFloat() - 0.5f;
            norm += v[i] * v[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dims; i++) v[i] /= norm;
        return v;
    }
}
