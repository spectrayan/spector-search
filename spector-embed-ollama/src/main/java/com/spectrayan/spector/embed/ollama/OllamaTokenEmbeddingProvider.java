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
import com.spectrayan.spector.embed.TokenEmbeddingProvider;
import com.spectrayan.spector.embed.TokenEmbeddingResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Dense-derived per-token embedding provider backed by Ollama.
 *
 * <h3>How It Works</h3>
 * <p>Since Ollama does not natively serve ColBERT models, this provider <b>derives</b>
 * per-token embeddings from the existing dense embedding model. The approach:</p>
 * <ol>
 *   <li>Tokenize text into constituent words</li>
 *   <li>Embed each word individually (batched via Ollama /api/embed)</li>
 *   <li>Project from model dimensionality (e.g., 1024) down to {@link #tokenDimensions()}
 *       via truncation (first N dimensions)</li>
 *   <li>Return per-token embedding matrix</li>
 * </ol>
 *
 * <h3>Dimension Projection</h3>
 * <p>Modern transformer embeddings exhibit <b>Matryoshka</b> properties — early
 * dimensions capture the majority of semantic information. Truncating from 1024→128
 * dimensions typically preserves 85-95% of retrieval quality. This avoids the
 * complexity of PCA while producing effective ColBERT-compatible embeddings.</p>
 *
 * <h3>Limitations vs True ColBERT</h3>
 * <ul>
 *   <li>True ColBERT uses a dedicated model trained for token-level late interaction;
 *       this uses a general-purpose embedding model.</li>
 *   <li>True ColBERT encodes the entire sequence at once (with cross-attention);
 *       this embeds each token independently (no cross-token context).</li>
 *   <li>Slower than ONNX ColBERT (one API call per token batch vs single forward pass).</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. All state is immutable or delegated to the
 * thread-safe {@link EmbeddingProvider}.</p>
 *
 * @see TokenEmbeddingProvider
 */
public class OllamaTokenEmbeddingProvider implements TokenEmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaTokenEmbeddingProvider.class);

    /** Pattern for tokenizing text into terms. */
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s\\p{Punct}]+");

    /** Default ColBERT token embedding dimensions. */
    private static final int DEFAULT_TOKEN_DIMS = 128;

    /** Minimum term length to include. */
    private static final int MIN_TERM_LENGTH = 2;

    private final EmbeddingProvider embeddingProvider;
    private final int tokenDims;
    private final String modelName;

    /**
     * Creates a token embedding provider using the given dense embedding provider.
     *
     * @param embeddingProvider the underlying dense embedding provider
     * @param tokenDims         per-token embedding dimensions (default: 128)
     */
    public OllamaTokenEmbeddingProvider(EmbeddingProvider embeddingProvider, int tokenDims) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.tokenDims = tokenDims;
        this.modelName = "dense-derived-colbert/" + embeddingProvider.modelName();
        log.info("OllamaTokenEmbeddingProvider initialized: model={}, tokenDims={}",
                modelName, tokenDims);
    }

    /**
     * Creates a provider with default 128 token dimensions.
     *
     * @param embeddingProvider the underlying dense embedding provider
     */
    public OllamaTokenEmbeddingProvider(EmbeddingProvider embeddingProvider) {
        this(embeddingProvider, DEFAULT_TOKEN_DIMS);
    }

    /**
     * Creates a provider backed by an Ollama embedding model.
     *
     * @param model the Ollama embedding model name (e.g., "qwen3-embedding")
     * @return configured provider
     */
    public static OllamaTokenEmbeddingProvider create(String model) {
        return new OllamaTokenEmbeddingProvider(OllamaEmbeddingProvider.create(model));
    }

    @Override
    public TokenEmbeddingResult encode(String text) {
        if (text == null || text.isBlank()) {
            return new TokenEmbeddingResult(new float[0][], new String[0], 0, modelName);
        }

        // Step 1: Tokenize into words
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return new TokenEmbeddingResult(new float[0][], new String[0], 0, modelName);
        }

        // Step 2: Batch-embed all tokens
        List<EmbeddingResult> embeddings = embeddingProvider.embedBatch(tokens);

        // Step 3: Project each embedding to tokenDims dimensions
        float[][] tokenEmbeddings = new float[tokens.size()][];
        for (int i = 0; i < tokens.size(); i++) {
            tokenEmbeddings[i] = project(embeddings.get(i).vector());
        }

        String[] tokenArray = tokens.toArray(new String[0]);

        log.debug("Token embedding: {} tokens × {}d → {}d projected",
                tokens.size(), embeddingProvider.dimensions(), tokenDims);

        return new TokenEmbeddingResult(tokenEmbeddings, tokenArray, tokens.size(), modelName);
    }

    @Override
    public int tokenDimensions() {
        return tokenDims;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    /**
     * Returns the underlying embedding provider.
     */
    public EmbeddingProvider embeddingProvider() {
        return embeddingProvider;
    }

    // ── Tokenization ──

    /**
     * Tokenizes text into a list of terms, preserving order (including duplicates
     * for MaxSim scoring — each occurrence matters for token-level matching).
     */
    private List<String> tokenize(String text) {
        String[] rawTokens = TOKEN_SPLITTER.split(text);
        List<String> tokens = new ArrayList<>();

        for (String token : rawTokens) {
            String trimmed = token.trim();
            if (trimmed.length() >= MIN_TERM_LENGTH && tokens.size() < maxTokens()) {
                tokens.add(trimmed.toLowerCase());
            }
        }

        return tokens;
    }

    // ── Projection ──

    /**
     * Projects a dense embedding vector to {@link #tokenDims} dimensions via truncation.
     *
     * <p>Takes the first {@code tokenDims} dimensions of the input vector.
     * Modern transformer embeddings with Matryoshka properties preserve most
     * semantic information in early dimensions, making truncation effective.</p>
     *
     * <p>If the input vector has fewer dimensions than {@code tokenDims},
     * the result is zero-padded.</p>
     */
    private float[] project(float[] fullVector) {
        float[] projected = new float[tokenDims];
        int len = Math.min(fullVector.length, tokenDims);
        System.arraycopy(fullVector, 0, projected, 0, len);
        return projected;
    }
}
