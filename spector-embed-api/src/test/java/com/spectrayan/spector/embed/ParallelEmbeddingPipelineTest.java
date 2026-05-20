package com.spectrayan.spector.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParallelEmbeddingPipeline}.
 */
class ParallelEmbeddingPipelineTest {

    @Test
    void emptyInputReturnsEmptyList() {
        var pipeline = new ParallelEmbeddingPipeline(stubProvider(384));
        List<PipelineEmbeddingResult> results = pipeline.embed(List.of(), EmbedConfig.DEFAULT);
        assertThat(results).isEmpty();
    }

    @Test
    void nullInputReturnsEmptyList() {
        var pipeline = new ParallelEmbeddingPipeline(stubProvider(384));
        List<PipelineEmbeddingResult> results = pipeline.embed(null, EmbedConfig.DEFAULT);
        assertThat(results).isEmpty();
    }

    @Test
    void singleChunkReturnsOneResult() {
        var pipeline = new ParallelEmbeddingPipeline(stubProvider(128));
        List<PipelineEmbeddingResult> results = pipeline.embed(List.of("hello"), new EmbedConfig(10, 0));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().success()).isTrue();
        assertThat(results.getFirst().embedding()).hasSize(128);
        assertThat(results.getFirst().chunkIndex()).isEqualTo(0);
    }

    @Test
    void orderingIsPreserved() {
        // Provider that encodes the text length as first element of the vector
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public EmbeddingResult embed(String text) {
                float[] v = new float[4];
                v[0] = text.length();
                return new EmbeddingResult(v, text.split("\\s+").length, "test");
            }

            @Override
            public List<EmbeddingResult> embedBatch(List<String> texts) {
                return texts.stream().map(this::embed).toList();
            }

            @Override
            public int dimensions() { return 4; }

            @Override
            public String modelName() { return "test"; }
        };

        var pipeline = new ParallelEmbeddingPipeline(provider);
        List<String> texts = List.of("a", "bb", "ccc", "dddd", "eeeee");
        List<PipelineEmbeddingResult> results = pipeline.embed(texts, new EmbedConfig(2, 0));

        assertThat(results).hasSize(5);
        for (int i = 0; i < texts.size(); i++) {
            assertThat(results.get(i).chunkIndex()).isEqualTo(i);
            assertThat(results.get(i).success()).isTrue();
            assertThat(results.get(i).embedding()[0]).isEqualTo((float) texts.get(i).length());
        }
    }

    @Test
    void retryOnFailureThenSucceeds() {
        AtomicInteger callCount = new AtomicInteger(0);
        EmbeddingProvider flakyProvider = new EmbeddingProvider() {
            @Override
            public EmbeddingResult embed(String text) {
                return new EmbeddingResult(new float[4], 1, "test");
            }

            @Override
            public List<EmbeddingResult> embedBatch(List<String> texts) {
                if (callCount.incrementAndGet() <= 2) {
                    throw new EmbeddingException("Temporary failure");
                }
                return texts.stream().map(this::embed).toList();
            }

            @Override
            public int dimensions() { return 4; }

            @Override
            public String modelName() { return "test"; }
        };

        var pipeline = new ParallelEmbeddingPipeline(flakyProvider);
        List<PipelineEmbeddingResult> results = pipeline.embed(List.of("text1"), new EmbedConfig(10, 3));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().success()).isTrue();
    }

    @Test
    void allRetriesExhaustedReportsFailure() {
        EmbeddingProvider alwaysFails = new EmbeddingProvider() {
            @Override
            public EmbeddingResult embed(String text) {
                throw new EmbeddingException("Always fails");
            }

            @Override
            public List<EmbeddingResult> embedBatch(List<String> texts) {
                throw new EmbeddingException("Always fails");
            }

            @Override
            public int dimensions() { return 4; }

            @Override
            public String modelName() { return "test"; }
        };

        var pipeline = new ParallelEmbeddingPipeline(alwaysFails);
        List<PipelineEmbeddingResult> results = pipeline.embed(
                List.of("text1", "text2"), new EmbedConfig(10, 2));

        assertThat(results).hasSize(2);
        for (PipelineEmbeddingResult r : results) {
            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("All retries exhausted");
        }
    }

    @Test
    void failedBatchDoesNotBlockOtherBatches() {
        AtomicInteger batchCallIndex = new AtomicInteger(0);
        // First batch call fails, second succeeds
        EmbeddingProvider partialFail = new EmbeddingProvider() {
            @Override
            public EmbeddingResult embed(String text) {
                return new EmbeddingResult(new float[4], 1, "test");
            }

            @Override
            public List<EmbeddingResult> embedBatch(List<String> texts) {
                // Fail if the batch contains "fail"
                if (texts.stream().anyMatch(t -> t.contains("fail"))) {
                    throw new EmbeddingException("Batch failed");
                }
                return texts.stream().map(this::embed).toList();
            }

            @Override
            public int dimensions() { return 4; }

            @Override
            public String modelName() { return "test"; }
        };

        var pipeline = new ParallelEmbeddingPipeline(partialFail);
        // Batch 1: ["fail_text"] — will fail; Batch 2: ["good_text"] — will succeed
        List<PipelineEmbeddingResult> results = pipeline.embed(
                List.of("fail_text", "good_text"), new EmbedConfig(1, 0));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).success()).isFalse(); // first batch failed
        assertThat(results.get(1).success()).isTrue();  // second batch succeeded
    }

    @Test
    void batchSizeRespected() {
        List<Integer> batchSizes = new ArrayList<>();
        EmbeddingProvider trackingProvider = new EmbeddingProvider() {
            @Override
            public EmbeddingResult embed(String text) {
                return new EmbeddingResult(new float[4], 1, "test");
            }

            @Override
            public synchronized List<EmbeddingResult> embedBatch(List<String> texts) {
                batchSizes.add(texts.size());
                return texts.stream().map(this::embed).toList();
            }

            @Override
            public int dimensions() { return 4; }

            @Override
            public String modelName() { return "test"; }
        };

        var pipeline = new ParallelEmbeddingPipeline(trackingProvider);
        List<String> texts = List.of("a", "b", "c", "d", "e");
        pipeline.embed(texts, new EmbedConfig(2, 0));

        // 5 texts with batch size 2 → batches of [2, 2, 1]
        assertThat(batchSizes).hasSize(3);
        assertThat(batchSizes).containsExactlyInAnyOrder(2, 2, 1);
    }

    /**
     * Creates a stub provider that returns zero vectors of the given dimension.
     */
    private static EmbeddingProvider stubProvider(int dimensions) {
        return new EmbeddingProvider() {
            @Override
            public EmbeddingResult embed(String text) {
                return new EmbeddingResult(new float[dimensions], text.split("\\s+").length, "stub");
            }

            @Override
            public List<EmbeddingResult> embedBatch(List<String> texts) {
                return texts.stream().map(this::embed).toList();
            }

            @Override
            public int dimensions() { return dimensions; }

            @Override
            public String modelName() { return "stub"; }
        };
    }
}
