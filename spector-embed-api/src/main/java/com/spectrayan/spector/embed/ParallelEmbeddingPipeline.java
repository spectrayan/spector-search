package com.spectrayan.spector.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parallel embedding pipeline that processes text chunks in configurable batches
 * using virtual threads.
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Configurable batch sizes for grouping chunks</li>
 *   <li>Virtual thread-based parallelism for concurrent batch processing</li>
 *   <li>Retry logic for failed batches with configurable retry count</li>
 *   <li>Failure isolation: failed batches don't block remaining batches</li>
 *   <li>Ordering preservation: output[i] always corresponds to input[i]</li>
 * </ul>
 *
 * <p>Validates: Requirements 7.1, 7.2, 7.3, 7.4</p>
 */
public class ParallelEmbeddingPipeline {

    private final EmbeddingProvider provider;

    /**
     * Creates a pipeline backed by the given embedding provider.
     *
     * @param provider the embedding provider to use for generating vectors
     */
    public ParallelEmbeddingPipeline(EmbeddingProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.provider = provider;
    }

    /**
     * Embeds a list of text chunks in parallel batches.
     *
     * <p>Chunks are split into batches of {@code config.batchSize()}, and each batch
     * is submitted to a virtual thread for concurrent processing. Failed batches are
     * retried up to {@code config.maxRetries()} times. If all retries are exhausted,
     * the failure is recorded and processing continues with remaining batches.</p>
     *
     * <p>The returned list maintains the same ordering as the input — the i-th result
     * corresponds to the i-th input text.</p>
     *
     * @param texts  list of text strings to embed
     * @param config pipeline configuration (batch size, max retries)
     * @return list of embedding results in the same order as input
     */
    public List<PipelineEmbeddingResult> embed(List<String> texts, EmbedConfig config) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (config == null) {
            config = EmbedConfig.DEFAULT;
        }

        int batchSize = config.batchSize();
        int maxRetries = config.maxRetries();
        int totalChunks = texts.size();

        // Split into batches
        List<List<String>> batches = partition(texts, batchSize);
        int numBatches = batches.size();

        // Results array preserving order; one sub-list per batch
        @SuppressWarnings("unchecked")
        List<PipelineEmbeddingResult>[] batchResults = new List[numBatches];

        // Process batches in parallel using virtual threads
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<PipelineEmbeddingResult>>> futures = new ArrayList<>(numBatches);

            for (int batchIdx = 0; batchIdx < numBatches; batchIdx++) {
                final int idx = batchIdx;
                final List<String> batch = batches.get(idx);
                final int startIndex = idx * batchSize;
                final int retries = maxRetries;

                futures.add(executor.submit(() -> processBatch(batch, startIndex, retries)));
            }

            // Collect results in order
            for (int i = 0; i < numBatches; i++) {
                try {
                    batchResults[i] = futures.get(i).get();
                } catch (Exception e) {
                    // Should not happen since processBatch handles errors internally,
                    // but handle defensively
                    List<String> batch = batches.get(i);
                    int startIndex = i * batchSize;
                    batchResults[i] = createFailureResults(batch, startIndex,
                            "Unexpected error: " + e.getMessage());
                }
            }
        }

        // Flatten batch results into a single ordered list
        List<PipelineEmbeddingResult> results = new ArrayList<>(totalChunks);
        for (List<PipelineEmbeddingResult> batchResult : batchResults) {
            results.addAll(batchResult);
        }
        return results;
    }

    /**
     * Processes a single batch with retry logic.
     *
     * @param batch      the texts in this batch
     * @param startIndex the global index of the first chunk in this batch
     * @param maxRetries maximum retry attempts
     * @return results for each chunk in the batch
     */
    private List<PipelineEmbeddingResult> processBatch(List<String> batch, int startIndex, int maxRetries) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                List<EmbeddingResult> embeddings = provider.embedBatch(batch);
                // Map provider results to pipeline results
                List<PipelineEmbeddingResult> results = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    int globalIndex = startIndex + i;
                    if (i < embeddings.size()) {
                        EmbeddingResult er = embeddings.get(i);
                        results.add(PipelineEmbeddingResult.success(globalIndex, er.vector()));
                    } else {
                        results.add(PipelineEmbeddingResult.failure(globalIndex,
                                "Provider returned fewer results than input size"));
                    }
                }
                return results;
            } catch (Exception e) {
                lastException = e;
                // Retry unless we've exhausted attempts
            }
        }

        // All retries exhausted — report failure for each chunk in the batch
        String errorMessage = "All retries exhausted"
                + (lastException != null ? ": " + lastException.getMessage() : "");
        return createFailureResults(batch, startIndex, errorMessage);
    }

    /**
     * Creates failure results for all items in a batch.
     */
    private List<PipelineEmbeddingResult> createFailureResults(List<String> batch, int startIndex, String error) {
        List<PipelineEmbeddingResult> results = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            results.add(PipelineEmbeddingResult.failure(startIndex + i, error));
        }
        return results;
    }

    /**
     * Partitions a list into sublists of the given size. The last partition may be smaller.
     */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
