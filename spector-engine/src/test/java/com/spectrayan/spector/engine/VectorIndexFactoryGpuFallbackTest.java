package com.spectrayan.spector.engine;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.spectrayan.spector.core.QuantizationType;

/**
 * Unit tests for GPU fallback logic in {@link VectorIndexFactory}.
 *
 * <p>Validates Requirement 8.5: GPU-accelerated distance computation for INT4/INT2
 * requires dimensions to be multiples of 32. When not aligned, the factory falls
 * back to CPU/SIMD and logs a warning.</p>
 */
class VectorIndexFactoryGpuFallbackTest {

    private final VectorIndexFactory factory = new VectorIndexFactory();

    @ParameterizedTest
    @ValueSource(ints = {100, 50, 33, 65, 127, 255})
    void int4_gpuEnabled_nonAlignedDimensions_fallsBackToCpu(int dims) {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(dims)
                .withQuantization(QuantizationType.SCALAR_INT4)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 50, 33, 65, 127, 255})
    void int2_gpuEnabled_nonAlignedDimensions_fallsBackToCpu(int dims) {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(dims)
                .withQuantization(QuantizationType.SCALAR_INT2)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64, 128, 256, 384, 512, 1024, 2048})
    void int4_gpuEnabled_alignedDimensions_keepsGpu(int dims) {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(dims)
                .withQuantization(QuantizationType.SCALAR_INT4)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64, 128, 256, 384, 512, 1024, 2048})
    void int2_gpuEnabled_alignedDimensions_keepsGpu(int dims) {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(dims)
                .withQuantization(QuantizationType.SCALAR_INT2)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isTrue();
    }

    @Test
    void int8_gpuEnabled_nonAlignedDimensions_noFallback() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(100)
                .withQuantization(QuantizationType.SCALAR_INT8)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isTrue();
    }

    @Test
    void none_gpuEnabled_nonAlignedDimensions_noFallback() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(100)
                .withQuantization(QuantizationType.NONE)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isTrue();
    }

    @Test
    void int4_gpuDisabled_nonAlignedDimensions_noChange() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(100)
                .withQuantization(QuantizationType.SCALAR_INT4)
                .withGpu(false);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isFalse();
    }

    @Test
    void fallback_preservesOtherConfigFields() {
        SpectorConfig config = SpectorConfig.DEFAULT
                .withDimensions(100)
                .withCapacity(50_000)
                .withQuantization(QuantizationType.SCALAR_INT4)
                .withGpu(true);

        SpectorConfig result = factory.applyGpuFallbackIfNeeded(config);

        assertThat(result.gpuEnabled()).isFalse();
        assertThat(result.dimensions()).isEqualTo(100);
        assertThat(result.capacity()).isEqualTo(50_000);
        assertThat(result.quantization()).isEqualTo(QuantizationType.SCALAR_INT4);
    }
}
