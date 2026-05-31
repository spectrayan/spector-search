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
package com.spectrayan.spector.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Spring Boot configuration properties for Spector.
 *
 * <p>Maps to the {@code spector.*} namespace in {@code application.yml} /
 * {@code application.properties}. Mirrors the existing {@code spector.yml}
 * schema so users can use the same property names they're familiar with.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 *   spector:
 *     engine:
 *       dimensions: 768
 *       capacity: 100000
 *       similarity: COSINE
 *     memory:
 *       enabled: true
 *       persistence-mode: DISK
 *       persistence-path: /data/spector/memory
 *     metrics:
 *       enabled: true
 * }</pre>
 */
@ConfigurationProperties("spector")
public class SpectorConfigProperties {

    private Engine engine = new Engine();
    private Memory memory = new Memory();
    private Metrics metrics = new Metrics();
    private Embedding embedding = new Embedding();

    public Engine getEngine() { return engine; }
    public void setEngine(Engine engine) { this.engine = engine; }
    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }
    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }

    // ─────────────── Engine ───────────────

    public static class Engine {
        private int dimensions = 768;
        private int capacity = 100_000;
        private String similarity = "COSINE";
        private String indexType = "HNSW";
        private String persistenceMode = "DISK";
        private String dataDirectory;

        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public String getSimilarity() { return similarity; }
        public void setSimilarity(String similarity) { this.similarity = similarity; }
        public String getIndexType() { return indexType; }
        public void setIndexType(String indexType) { this.indexType = indexType; }
        public String getPersistenceMode() { return persistenceMode; }
        public void setPersistenceMode(String persistenceMode) { this.persistenceMode = persistenceMode; }
        public String getDataDirectory() { return dataDirectory; }
        public void setDataDirectory(String dataDirectory) { this.dataDirectory = dataDirectory; }
    }

    // ─────────────── Memory ───────────────

    public static class Memory {
        private boolean enabled = false;
        private String persistenceMode = "DISK";
        private String persistencePath;
        private int dimensions = 768;
        private int capacity = 100_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPersistenceMode() { return persistenceMode; }
        public void setPersistenceMode(String persistenceMode) { this.persistenceMode = persistenceMode; }
        public String getPersistencePath() { return persistencePath; }
        public void setPersistencePath(String persistencePath) { this.persistencePath = persistencePath; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
    }

    // ─────────────── Metrics ───────────────

    public static class Metrics {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    // ─────────────── Embedding ───────────────

    public static class Embedding {
        private String model = "nomic-embed-text";
        private String baseUrl = "http://localhost:11434";

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    /**
     * Converts engine properties to a {@link com.spectrayan.spector.config.SpectorConfig}.
     */
    public com.spectrayan.spector.config.SpectorConfig toEngineConfig() {
        var config = com.spectrayan.spector.config.SpectorConfig.DEFAULT
                .withDimensions(engine.dimensions)
                .withCapacity(engine.capacity)
                .withSimilarityFunction(
                        com.spectrayan.spector.core.similarity.SimilarityFunction.valueOf(engine.similarity));

        if (engine.dataDirectory != null) {
            config = config.withPersistence(
                    com.spectrayan.spector.config.PersistenceMode.valueOf(engine.persistenceMode),
                    Path.of(engine.dataDirectory));
        }

        return config;
    }
}
