package com.spectrayan.spector.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests MappedVectorStore ID mapping save/load round-trip.
 */
class MappedVectorStoreIdRecoveryTest {

    @TempDir
    Path tmpDir;

    @Test
    void saveAndLoadIdMappings_preservesLookups() throws IOException {
        Path vectorFile = tmpDir.resolve("vectors.mmap");
        Path idFile = tmpDir.resolve("id-mappings.dat");

        int dims = 4;
        int capacity = 100;

        // Create, put vectors, save ID mappings
        try (var store = new MappedVectorStore(vectorFile, dims, capacity)) {
            store.put("vec-alpha", new float[]{1.0f, 2.0f, 3.0f, 4.0f});
            store.put("vec-beta", new float[]{5.0f, 6.0f, 7.0f, 8.0f});
            store.put("vec-gamma", new float[]{9.0f, 10.0f, 11.0f, 12.0f});

            assertThat(store.size()).isEqualTo(3);
            assertThat(store.indexOf("vec-alpha")).isEqualTo(0);
            assertThat(store.indexOf("vec-beta")).isEqualTo(1);
            assertThat(store.indexOf("vec-gamma")).isEqualTo(2);

            store.saveIdMappings(idFile);
        }

        // Reopen and load ID mappings — simulating a JVM restart
        try (var store = new MappedVectorStore(vectorFile, dims, capacity)) {
            // Before loading, idToIndex is empty
            assertThat(store.indexOf("vec-alpha")).isEqualTo(-1);

            // Load ID mappings
            store.loadIdMappings(idFile);

            // Now lookups should work
            assertThat(store.size()).isEqualTo(3);
            assertThat(store.indexOf("vec-alpha")).isEqualTo(0);
            assertThat(store.indexOf("vec-beta")).isEqualTo(1);
            assertThat(store.indexOf("vec-gamma")).isEqualTo(2);

            // Verify vector data survived via mmap
            float[] alpha = store.get("vec-alpha");
            assertThat(alpha).isNotNull();
            assertThat(alpha).containsExactly(1.0f, 2.0f, 3.0f, 4.0f);

            float[] gamma = store.get("vec-gamma");
            assertThat(gamma).containsExactly(9.0f, 10.0f, 11.0f, 12.0f);
        }
    }

    @Test
    void loadIdMappings_missingFile_noOp() throws IOException {
        Path vectorFile = tmpDir.resolve("vectors2.mmap");
        try (var store = new MappedVectorStore(vectorFile, 4, 10)) {
            store.loadIdMappings(tmpDir.resolve("nonexistent.dat"));
            assertThat(store.size()).isEqualTo(0);
        }
    }

    @Test
    void loadIdMappings_nullPath_noOp() throws IOException {
        Path vectorFile = tmpDir.resolve("vectors3.mmap");
        try (var store = new MappedVectorStore(vectorFile, 4, 10)) {
            store.loadIdMappings(null);
            assertThat(store.size()).isEqualTo(0);
        }
    }
}
