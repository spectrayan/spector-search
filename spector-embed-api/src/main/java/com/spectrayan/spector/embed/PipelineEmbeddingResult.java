package com.spectrayan.spector.embed;

/**
 * Result of embedding a single text chunk in the pipeline.
 *
 * <p>Captures both success (embedding vector present) and failure (error message present)
 * for each chunk processed by the {@link ParallelEmbeddingPipeline}.</p>
 *
 * @param chunkIndex the index of the input chunk this result corresponds to
 * @param embedding  the embedding vector (null if the embedding failed)
 * @param success    whether the embedding succeeded
 * @param error      error message if the embedding failed (null on success)
 */
public record PipelineEmbeddingResult(int chunkIndex, float[] embedding, boolean success, String error) {

    /**
     * Creates a successful result.
     */
    public static PipelineEmbeddingResult success(int chunkIndex, float[] embedding) {
        return new PipelineEmbeddingResult(chunkIndex, embedding, true, null);
    }

    /**
     * Creates a failed result.
     */
    public static PipelineEmbeddingResult failure(int chunkIndex, String error) {
        return new PipelineEmbeddingResult(chunkIndex, null, false, error);
    }
}
