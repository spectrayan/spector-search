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
package com.spectrayan.spector.embed;

/**
 * Result of an embedding operation.
 *
 * @param vector     the dense embedding vector
 * @param tokenCount number of tokens consumed from the input text (-1 if unknown)
 * @param model      the model that produced this embedding
 */
public record EmbeddingResult(
        float[] vector,
        int tokenCount,
        String model
) {
    /**
     * Creates a result with unknown token count.
     */
    public static EmbeddingResult of(float[] vector, String model) {
        return new EmbeddingResult(vector, -1, model);
    }

    /**
     * Returns the dimensionality of the vector.
     */
    public int dimensions() {
        return vector.length;
    }
}
