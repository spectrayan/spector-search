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
package com.spectrayan.spector.embed.ollama;

import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.EmbeddingResult;
import com.spectrayan.spector.embed.SparseEncodingProvider;
import com.spectrayan.spector.embed.SparseEncodingResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Dense-derived sparse encoding provider backed by Ollama.
 *
 * <h3>How It Works</h3>
 * <p>Since Ollama does not natively serve SPLADE models, this provider <b>derives</b>
 * sparse term weights from the existing dense embedding model. The approach:</p>
 * <ol>
 *   <li>Embed the full text → dense document vector D</li>
 *   <li>Tokenize text into constituent terms</li>
 *   <li>Embed each term individually (batched) → [T₁, T₂, ..., Tₙ]</li>
 *   <li>For each term: weight = max(0, cosine(Tᵢ, D))</li>
 *   <li>Filter terms below threshold; return as sparse map</li>
 * </ol>
 *
 * <p>This produces meaningful sparse vectors: terms whose individual embeddings
 * align most with the document's overall meaning get the highest weights —
 * similar in spirit to SPLADE's learned importance scores.</p>
 *
 * <h3>Limitations vs True SPLADE</h3>
 * <ul>
 *   <li>No <b>term expansion</b> — only terms present in the text are scored.
 *       True SPLADE expands vocabulary (e.g., "car" activates "vehicle").</li>
 *   <li>Weights approximate rather than model learned importance.</li>
 *   <li>Slower than ONNX SPLADE (one embedding call per term vs single forward pass).</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. All state is immutable or delegated to the
 * thread-safe {@link EmbeddingProvider}.</p>
 *
 * @see SparseEncodingProvider
 */
public class OllamaSparseEncodingProvider implements SparseEncodingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaSparseEncodingProvider.class);

    /** Pattern for tokenizing text into terms: split on whitespace + punctuation boundaries. */
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s\\p{Punct}]+");

    /** Minimum term length to include in sparse vector (skip single chars). */
    private static final int MIN_TERM_LENGTH = 2;

    /** Maximum number of unique terms to embed per document. */
    private static final int MAX_TERMS = 200;

    private final EmbeddingProvider embeddingProvider;
    private final float weightThreshold;
    private final String modelName;

    /**
     * Creates a sparse encoding provider using the given dense embedding provider.
     *
     * @param embeddingProvider the underlying dense embedding provider (e.g., Ollama)
     * @param weightThreshold   minimum cosine similarity for a term to be included (default: 0.1)
     */
    public OllamaSparseEncodingProvider(EmbeddingProvider embeddingProvider, float weightThreshold) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.weightThreshold = weightThreshold;
        this.modelName = "dense-derived-splade/" + embeddingProvider.modelName();
        log.info("OllamaSparseEncodingProvider initialized: model={}, threshold={}",
                modelName, weightThreshold);
    }

    /**
     * Creates a provider with the default weight threshold (0.1).
     *
     * @param embeddingProvider the underlying dense embedding provider
     */
    public OllamaSparseEncodingProvider(EmbeddingProvider embeddingProvider) {
        this(embeddingProvider, 0.1f);
    }

    /**
     * Creates a provider backed by an Ollama embedding model.
     *
     * @param model the Ollama embedding model name (e.g., "qwen3-embedding")
     * @return configured provider
     */
    public static OllamaSparseEncodingProvider create(String model) {
        return new OllamaSparseEncodingProvider(OllamaEmbeddingProvider.create(model));
    }

    @Override
    public SparseEncodingResult encode(String text) {
        if (text == null || text.isBlank()) {
            return new SparseEncodingResult(Map.of(), 0, modelName);
        }

        // Step 1: Embed the full document text
        EmbeddingResult docEmbedding = embeddingProvider.embed(text);
        float[] docVec = docEmbedding.vector();

        // Step 2: Tokenize into unique terms
        List<String> terms = tokenize(text);
        if (terms.isEmpty()) {
            return new SparseEncodingResult(Map.of(), 0, modelName);
        }

        // Step 3: Batch-embed all terms
        List<EmbeddingResult> termEmbeddings = embeddingProvider.embedBatch(terms);

        // Step 4: Compute cosine similarity between each term embedding and doc embedding
        Map<String, Float> weights = new LinkedHashMap<>();
        for (int i = 0; i < terms.size(); i++) {
            float[] termVec = termEmbeddings.get(i).vector();
            float cosine = cosineSimilarity(termVec, docVec);
            float weight = Math.max(0f, cosine);

            if (weight >= weightThreshold) {
                String term = terms.get(i).toLowerCase();
                // Keep the higher weight if a term appears multiple times
                weights.merge(term, weight, Math::max);
            }
        }

        log.debug("Sparse encoding: {} terms → {} non-zero weights (threshold={})",
                terms.size(), weights.size(), weightThreshold);

        return new SparseEncodingResult(weights, terms.size(), modelName);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public int vocabularySize() {
        // Dynamic — based on actual terms in documents. Return a large nominal value.
        return 50_000;
    }

    @Override
    public SparseEncodingType type() {
        return SparseEncodingType.SPLADE;
    }

    /**
     * Returns the underlying embedding provider.
     */
    public EmbeddingProvider embeddingProvider() {
        return embeddingProvider;
    }

    // ── Tokenization ──

    /**
     * Tokenizes text into a list of unique terms, preserving order.
     * Filters by minimum length and caps at MAX_TERMS.
     */
    private List<String> tokenize(String text) {
        String[] rawTokens = TOKEN_SPLITTER.split(text);
        Set<String> seen = new LinkedHashSet<>();

        for (String token : rawTokens) {
            String lower = token.toLowerCase().trim();
            if (lower.length() >= MIN_TERM_LENGTH && seen.size() < MAX_TERMS) {
                seen.add(lower);
            }
        }

        return new ArrayList<>(seen);
    }

    // ── Cosine Similarity ──

    /**
     * Computes cosine similarity between two vectors.
     */
    private static float cosineSimilarity(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        float dot = 0f, normA = 0f, normB = 0f;

        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom > 0f ? dot / denom : 0f;
    }
}
