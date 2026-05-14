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
