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
package com.spectrayan.spector.storage;

import com.spectrayan.spector.commons.error.SpectorValidationException;

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
                .isInstanceOf(SpectorValidationException.class);
        assertThatThrownBy(() -> new VectorStoreLayout(-1))
                .isInstanceOf(SpectorValidationException.class);
    }
}
