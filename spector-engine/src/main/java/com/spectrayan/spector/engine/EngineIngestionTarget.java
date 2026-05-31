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
package com.spectrayan.spector.engine;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.config.IndexType;
import com.spectrayan.spector.config.SpectorConfig;
import com.spectrayan.spector.index.KeywordIndex;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.index.ivf.IvfPqIndex;
import com.spectrayan.spector.index.spectrum.SpectorIndex;
import com.spectrayan.spector.ingestion.IngestionTarget;
import com.spectrayan.spector.storage.Document;
import com.spectrayan.spector.storage.DocumentStore;
import com.spectrayan.spector.storage.VectorStore;

/**
 * Engine-side implementation of {@link IngestionTarget}.
 *
 * <p>Routes each ingested chunk to the engine's storage subsystems:
 * VectorStore (off-heap) → VectorIndex (HNSW/IVF/Spectrum) → KeywordIndex (BM25).</p>
 *
 * <h3>IVF-PQ / Spectrum Auto-Training</h3>
 * <p>For IVF-PQ and Spectrum index types, vectors are buffered until enough
 * training samples are collected. During buffering, documents are still
 * indexed for keyword search but not added to the vector index.</p>
 *
 * @see IngestionTarget
 */
public final class EngineIngestionTarget implements IngestionTarget {

    private static final Logger log = LoggerFactory.getLogger(EngineIngestionTarget.class);

    private final SpectorConfig config;
    private final VectorStore vectorStore;
    private final DocumentStore documentStore;
    private final VectorIndex vectorIndex;
    private final KeywordIndex keywordIndex;

    // IVF-PQ training state
    private List<float[]> ivfTrainingBuffer;
    private List<String> ivfTrainingIds;
    private List<String> ivfTrainingContents;
    private volatile boolean ivfTrained;

    // Spectrum training state
    private List<float[]> spectrumTrainingBuffer;
    private List<String> spectrumTrainingIds;
    private List<String> spectrumTrainingContents;
    private volatile boolean spectrumTrained;

    public EngineIngestionTarget(SpectorConfig config, VectorStore vectorStore,
                                  DocumentStore documentStore, VectorIndex vectorIndex,
                                  KeywordIndex keywordIndex) {
        this.config = config;
        this.vectorStore = vectorStore;
        this.documentStore = documentStore;
        this.vectorIndex = vectorIndex;
        this.keywordIndex = keywordIndex;
        this.ivfTrained = false;
        this.spectrumTrained = false;

        // IVF-PQ training buffer initialization
        if (config.indexType() == IndexType.IVF_PQ) {
            int minTrainingSamples = Math.max(config.effectiveNlist() * 40, 256);
            this.ivfTrainingBuffer = new ArrayList<>(minTrainingSamples);
            this.ivfTrainingIds = new ArrayList<>(minTrainingSamples);
            this.ivfTrainingContents = new ArrayList<>(minTrainingSamples);
            log.info("IVF-PQ training: will auto-train after {} vectors.", minTrainingSamples);
        }

        // Spectrum training buffer initialization
        if (config.indexType() == IndexType.SPECTRUM) {
            int minTrainingSamples = Math.max(config.effectiveSpectrumNCentroids() * 40, 256);
            this.spectrumTrainingBuffer = new ArrayList<>(minTrainingSamples);
            this.spectrumTrainingIds = new ArrayList<>(minTrainingSamples);
            this.spectrumTrainingContents = new ArrayList<>(minTrainingSamples);
            log.info("Spectrum training: will auto-train after {} vectors.", minTrainingSamples);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // IngestionTarget implementation
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void ingest(String id, String text, float[] vector) {
        // IVF-PQ auto-training: buffer vectors until we have enough to train
        if (config.indexType() == IndexType.IVF_PQ && !ivfTrained) {
            ivfTrainingBuffer.add(vector.clone());
            ivfTrainingIds.add(id);
            ivfTrainingContents.add(text);

            int minSamples = Math.max(config.effectiveNlist() * 40, 256);
            if (ivfTrainingBuffer.size() >= minSamples) {
                trainAndFlushIvfPq();
            } else {
                documentStore.put(Document.of(id, text));
                keywordIndex.index(id, text);
            }
            return;
        }

        // Spectrum auto-training: buffer vectors until we have enough to train
        if (config.indexType() == IndexType.SPECTRUM && !spectrumTrained) {
            spectrumTrainingBuffer.add(vector.clone());
            spectrumTrainingIds.add(id);
            spectrumTrainingContents.add(text);

            int minSamples = Math.max(config.effectiveSpectrumNCentroids() * 40, 256);
            if (spectrumTrainingBuffer.size() >= minSamples) {
                trainAndFlushSpectrum();
            } else {
                documentStore.put(Document.of(id, text));
                keywordIndex.index(id, text);
            }
            return;
        }

        // Normal ingestion path
        int storeIndex = vectorStore.put(id, vector);
        vectorIndex.add(id, storeIndex, vector);
        keywordIndex.index(id, text);
    }

    @Override
    public void storeParentMetadata(String parentId, int chunkCount) {
        documentStore.put(Document.of(parentId, "[chunked: " + chunkCount + " chunks]"));
    }

    // ═══════════════════════════════════════════════════════════════
    // IVF-PQ / Spectrum training
    // ═══════════════════════════════════════════════════════════════

    private void trainAndFlushIvfPq() {
        log.info("Training IVF-PQ with {} vectors...", ivfTrainingBuffer.size());
        float[][] trainingData = ivfTrainingBuffer.toArray(float[][]::new);
        ((IvfPqIndex) vectorIndex).train(trainingData);
        ivfTrained = true;

        // Flush buffered vectors
        for (int i = 0; i < ivfTrainingBuffer.size(); i++) {
            float[] vec = ivfTrainingBuffer.get(i);
            String bufferedId = ivfTrainingIds.get(i);
            String bufferedContent = ivfTrainingContents.get(i);

            int storeIndex = vectorStore.put(bufferedId, vec);
            documentStore.put(Document.of(bufferedId, bufferedContent));
            vectorIndex.add(bufferedId, storeIndex, vec);
            keywordIndex.index(bufferedId, bufferedContent);
        }

        // Free training buffers
        ivfTrainingBuffer = null;
        ivfTrainingIds = null;
        ivfTrainingContents = null;
        log.info("IVF-PQ trained and {} buffered vectors flushed.", trainingData.length);
    }

    private void trainAndFlushSpectrum() {
        log.info("Training Spectrum with {} vectors...", spectrumTrainingBuffer.size());
        float[][] trainingData = spectrumTrainingBuffer.toArray(float[][]::new);
        ((SpectorIndex) vectorIndex).train(trainingData);
        spectrumTrained = true;

        // Flush buffered vectors
        for (int i = 0; i < spectrumTrainingBuffer.size(); i++) {
            float[] vec = spectrumTrainingBuffer.get(i);
            String bufferedId = spectrumTrainingIds.get(i);
            String bufferedContent = spectrumTrainingContents.get(i);

            int storeIndex = vectorStore.put(bufferedId, vec);
            documentStore.put(Document.of(bufferedId, bufferedContent));
            vectorIndex.add(bufferedId, storeIndex, vec);
            keywordIndex.index(bufferedId, bufferedContent);
        }

        // Free training buffers
        spectrumTrainingBuffer = null;
        spectrumTrainingIds = null;
        spectrumTrainingContents = null;
        log.info("Spectrum trained and {} buffered vectors flushed.", trainingData.length);
    }
}
