package com.spectrayan.spector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Tests for {@link SpectorProperties} hierarchical configuration loading.
 */
class SpectorPropertiesTest {

    @Test
    void loadDefaults_returnsClasspathValues() {
        SpectorProperties props = SpectorProperties.load();

        // Verify values from spector-defaults.yml
        assertThat(props.getInt("spector.engine.dimensions", -1)).isEqualTo(384);
        assertThat(props.getInt("spector.engine.capacity", -1)).isEqualTo(100_000);
        assertThat(props.getString("spector.engine.similarity", "")).isEqualTo("COSINE");
        assertThat(props.getString("spector.engine.index-type", "")).isEqualTo("HNSW");
        assertThat(props.getString("spector.engine.quantization", "")).isEqualTo("NONE");
        assertThat(props.getString("spector.engine.persistence-mode", "")).isEqualTo("IN_MEMORY");
    }

    @Test
    void loadDefaults_hnswParams() {
        SpectorProperties props = SpectorProperties.load();

        assertThat(props.getInt("spector.hnsw.m", -1)).isEqualTo(16);
        assertThat(props.getInt("spector.hnsw.ef-construction", -1)).isEqualTo(200);
        assertThat(props.getInt("spector.hnsw.ef-search", -1)).isEqualTo(50);
    }

    @Test
    void loadDefaults_embeddingConfig() {
        SpectorProperties props = SpectorProperties.load();

        assertThat(props.getString("spector.embedding.model")).isEqualTo("nomic-embed-text");
        assertThat(props.getString("spector.embedding.base-url")).isEqualTo("http://localhost:11434");
        assertThat(props.getInt("spector.embedding.batch-size", -1)).isEqualTo(32);
        assertThat(props.getInt("spector.embedding.max-retries", -1)).isEqualTo(3);
    }

    @Test
    void loadDefaults_persistenceFiles() {
        SpectorProperties props = SpectorProperties.load();

        assertThat(props.getString("spector.persistence.files.index")).isEqualTo("index.spct");
        assertThat(props.getString("spector.persistence.files.vectors")).isEqualTo("vectors.mmap");
        assertThat(props.getString("spector.persistence.files.documents")).isEqualTo("documents.dat");
        assertThat(props.getString("spector.persistence.files.id-mappings")).isEqualTo("id-mappings.dat");
    }

    @Test
    void loadDefaults_ragConfig() {
        SpectorProperties props = SpectorProperties.load();

        assertThat(props.getInt("spector.rag.top-k", -1)).isEqualTo(5);
        assertThat(props.getFloat("spector.rag.similarity-threshold", -1f)).isEqualTo(0.7f);
        assertThat(props.getInt("spector.rag.token-limit", -1)).isEqualTo(4096);
    }

    @Test
    void duration_humanReadable() {
        SpectorProperties props = SpectorProperties.builder()
                .override("timeout.seconds", "30s")
                .override("timeout.millis", "500ms")
                .override("timeout.minutes", "5m")
                .override("timeout.hours", "1h")
                .build();

        assertThat(props.getDuration("timeout.seconds", Duration.ZERO)).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.getDuration("timeout.millis", Duration.ZERO)).isEqualTo(Duration.ofMillis(500));
        assertThat(props.getDuration("timeout.minutes", Duration.ZERO)).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getDuration("timeout.hours", Duration.ZERO)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void duration_iso8601() {
        SpectorProperties props = SpectorProperties.builder()
                .override("timeout", "PT45S")
                .build();

        assertThat(props.getDuration("timeout", Duration.ZERO)).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void enum_resolution() {
        SpectorProperties props = SpectorProperties.builder()
                .override("mode", "COSINE")
                .build();

        assertThat(props.getEnum("mode", TestEnum.class, TestEnum.EUCLIDEAN))
                .isEqualTo(TestEnum.COSINE);
    }

    @Test
    void enum_caseInsensitiveWithHyphen() {
        SpectorProperties props = SpectorProperties.builder()
                .override("mode", "in-memory")
                .build();

        assertThat(props.getEnum("mode", TestPersistence.class, TestPersistence.DISK))
                .isEqualTo(TestPersistence.IN_MEMORY);
    }

    @Test
    void enum_invalidFallsBackToDefault() {
        SpectorProperties props = SpectorProperties.builder()
                .override("mode", "INVALID")
                .build();

        assertThat(props.getEnum("mode", TestEnum.class, TestEnum.EUCLIDEAN))
                .isEqualTo(TestEnum.EUCLIDEAN);
    }

    @Test
    void programmaticOverrides_winOverDefaults() {
        SpectorProperties props = SpectorProperties.builder()
                .override("spector.engine.dimensions", "768")
                .build();

        assertThat(props.getInt("spector.engine.dimensions", -1)).isEqualTo(768);
    }

    @Test
    void systemProperties_winOverFileConfig() {
        String key = "spector.test.sysprop.key";
        System.setProperty(key, "from-system");
        try {
            SpectorProperties props = SpectorProperties.builder()
                    .override(key, "from-override")
                    .build();

            // System properties win over everything in resolveWithEnv
            assertThat(props.getString(key)).isEqualTo("from-system");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void yamlFileOverride(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("spector.yml");
        Files.writeString(configFile, """
                spector:
                  engine:
                    dimensions: 1024
                    capacity: 500000
                """);

        SpectorProperties props = SpectorProperties.load(configFile);

        assertThat(props.getInt("spector.engine.dimensions", -1)).isEqualTo(1024);
        assertThat(props.getInt("spector.engine.capacity", -1)).isEqualTo(500_000);
        // Other values still come from classpath defaults
        assertThat(props.getString("spector.engine.similarity", "")).isEqualTo("COSINE");
    }

    @Test
    void propertiesFileOverride(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("custom.properties");
        Files.writeString(configFile, """
                spector.engine.dimensions=2048
                spector.embedding.model=mxbai-embed-large
                """);

        SpectorProperties props = SpectorProperties.builder()
                .configFile(configFile)
                .build();

        assertThat(props.getInt("spector.engine.dimensions", -1)).isEqualTo(2048);
        assertThat(props.getString("spector.embedding.model")).isEqualTo("mxbai-embed-large");
    }

    @Test
    void missingKey_returnsDefault() {
        SpectorProperties props = SpectorProperties.load();

        assertThat(props.getString("nonexistent.key")).isNull();
        assertThat(props.getString("nonexistent.key", "fallback")).isEqualTo("fallback");
        assertThat(props.getInt("nonexistent.key", 42)).isEqualTo(42);
        assertThat(props.getBoolean("nonexistent.key", true)).isTrue();
    }

    @Test
    void containsKey() {
        SpectorProperties props = SpectorProperties.load();

        assertThat(props.containsKey("spector.engine.dimensions")).isTrue();
        assertThat(props.containsKey("nonexistent.key")).isFalse();
    }

    @Test
    void path_resolution() {
        SpectorProperties props = SpectorProperties.builder()
                .override("data.dir", "/tmp/spector")
                .build();

        assertThat(props.getPath("data.dir", null)).isEqualTo(Path.of("/tmp/spector"));
        assertThat(props.getPath("missing.key", Path.of("/default"))).isEqualTo(Path.of("/default"));
    }

    @Test
    void persistenceFiles_fromProperties() {
        SpectorProperties props = SpectorProperties.builder()
                .override("spector.persistence.files.index", "custom-index.bin")
                .override("spector.persistence.files.vectors", "custom-vectors.bin")
                .build();

        PersistenceFiles files = PersistenceFiles.from(props);

        assertThat(files.indexFile()).isEqualTo("custom-index.bin");
        assertThat(files.vectorsFile()).isEqualTo("custom-vectors.bin");
        // Non-overridden use defaults
        assertThat(files.documentsFile()).isEqualTo("documents.dat");
        assertThat(files.idMappingsFile()).isEqualTo("id-mappings.dat");
    }

    @Test
    void persistenceFiles_defaultValues() {
        PersistenceFiles files = PersistenceFiles.DEFAULTS;

        assertThat(files.indexFile()).isEqualTo("index.spct");
        assertThat(files.vectorsFile()).isEqualTo("vectors.mmap");
        assertThat(files.documentsFile()).isEqualTo("documents.dat");
        assertThat(files.idMappingsFile()).isEqualTo("id-mappings.dat");
    }

    @Test
    void persistenceFiles_resolvePaths() {
        Path dataDir = Path.of("/data/spector");
        PersistenceFiles files = PersistenceFiles.DEFAULTS;

        assertThat(files.resolveIndex(dataDir)).isEqualTo(Path.of("/data/spector/index.spct"));
        assertThat(files.resolveVectors(dataDir)).isEqualTo(Path.of("/data/spector/vectors.mmap"));
        assertThat(files.resolveDocuments(dataDir)).isEqualTo(Path.of("/data/spector/documents.dat"));
        assertThat(files.resolveIdMappings(dataDir)).isEqualTo(Path.of("/data/spector/id-mappings.dat"));
    }

    @Test
    void configFile_notFound_throws() {
        assertThatThrownBy(() -> SpectorProperties.load(Path.of("/nonexistent/config.yml")))
                .isInstanceOf(com.spectrayan.spector.commons.error.SpectorConfigNotFoundException.class)
                .hasMessageContaining("not found");
    }

    // Test enums
    enum TestEnum { COSINE, EUCLIDEAN }
    enum TestPersistence { IN_MEMORY, DISK }
}
