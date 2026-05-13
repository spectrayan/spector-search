package com.spectrayan.spector.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VectorStoreLayout}.
 */
class VectorStoreLayoutTest {

    @Test
    void vectorByteSize() {
        var layout = new VectorStoreLayout(384);
        // 384 floats × 4 bytes = 1536 bytes
        assertThat(layout.vectorByteSize()).isEqualTo(384L * 4L);
    }

    @Test
    void vectorOffset() {
        var layout = new VectorStoreLayout(3);
        // vector 0 at byte 0, vector 1 at byte 12, vector 2 at byte 24
        assertThat(layout.vectorOffset(0)).isEqualTo(0L);
        assertThat(layout.vectorOffset(1)).isEqualTo(12L);
        assertThat(layout.vectorOffset(2)).isEqualTo(24L);
    }

    @Test
    void elementOffset() {
        var layout = new VectorStoreLayout(3);
        // vector 1, element 2 = 12 + 8 = 20
        assertThat(layout.elementOffset(1, 2)).isEqualTo(20L);
    }

    @Test
    void totalByteSize() {
        var layout = new VectorStoreLayout(128);
        assertThat(layout.totalByteSize(1000)).isEqualTo(128L * 4L * 1000L);
    }

    @Test
    void invalidDimensionsThrows() {
        assertThatThrownBy(() -> new VectorStoreLayout(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VectorStoreLayout(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
