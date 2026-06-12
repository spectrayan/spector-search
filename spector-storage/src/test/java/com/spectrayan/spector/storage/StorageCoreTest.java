/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.storage;

import static org.assertj.core.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for storage DTOs and layout — Document, VectorStoreLayout.
 */
@DisplayName("Storage Core Types")
class StorageCoreTest {

    // ══════════════════════════════════════════════════════════════
    // Document
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Document record")
    class DocumentTests {

        @Test @DisplayName("full constructor preserves all fields")
        void fullConstructor() {
            var doc = new Document("doc-1", "Title", "Content", Map.of("key", "value"));
            assertThat(doc.id()).isEqualTo("doc-1");
            assertThat(doc.title()).isEqualTo("Title");
            assertThat(doc.content()).isEqualTo("Content");
            assertThat(doc.metadata()).containsEntry("key", "value");
        }

        @Test @DisplayName("of(id, content) factory")
        void ofIdContent() {
            var doc = Document.of("doc-1", "Content");
            assertThat(doc.id()).isEqualTo("doc-1");
            assertThat(doc.title()).isEmpty();
            assertThat(doc.content()).isEqualTo("Content");
            assertThat(doc.metadata()).isEmpty();
        }

        @Test @DisplayName("of(id, title, content) factory")
        void ofIdTitleContent() {
            var doc = Document.of("doc-1", "My Title", "Content");
            assertThat(doc.title()).isEqualTo("My Title");
        }

        @Test @DisplayName("null title defaults to empty string")
        void nullTitle() {
            var doc = new Document("doc-1", null, "Content", Map.of());
            assertThat(doc.title()).isEmpty();
        }

        @Test @DisplayName("null metadata defaults to empty map")
        void nullMetadata() {
            var doc = new Document("doc-1", "Title", "Content", null);
            assertThat(doc.metadata()).isEmpty();
        }

        @Test @DisplayName("metadata is immutable copy")
        void immutableMetadata() {
            var original = new java.util.HashMap<String, Object>();
            original.put("key", "value");
            var doc = new Document("doc-1", "Title", "Content", original);
            original.put("key2", "value2");
            assertThat(doc.metadata()).doesNotContainKey("key2");
        }

        @Test @DisplayName("rejects null id")
        void nullId() {
            assertThatThrownBy(() -> new Document(null, "Title", "Content", Map.of()))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects null content")
        void nullContent() {
            assertThatThrownBy(() -> new Document("doc-1", "Title", null, Map.of()))
                    .isInstanceOf(SpectorValidationException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // VectorStoreLayout
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("VectorStoreLayout")
    class VectorStoreLayoutTests {

        @Test @DisplayName("construction with valid dimensions")
        void validConstruction() {
            var layout = new VectorStoreLayout(384);
            assertThat(layout.dimensions()).isEqualTo(384);
        }

        @Test @DisplayName("rejects zero dimensions")
        void zeroDimensions() {
            assertThatThrownBy(() -> new VectorStoreLayout(0))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("rejects negative dimensions")
        void negativeDimensions() {
            assertThatThrownBy(() -> new VectorStoreLayout(-1))
                    .isInstanceOf(SpectorValidationException.class);
        }

        @Test @DisplayName("vectorByteSize is dimensions * 4")
        void vectorByteSize() {
            var layout = new VectorStoreLayout(384);
            assertThat(layout.vectorByteSize()).isEqualTo(384L * 4);
        }

        @Test @DisplayName("vectorOffset for index 0 is 0")
        void vectorOffsetZero() {
            var layout = new VectorStoreLayout(128);
            assertThat(layout.vectorOffset(0)).isZero();
        }

        @Test @DisplayName("vectorOffset for index N is N * vectorByteSize")
        void vectorOffsetN() {
            var layout = new VectorStoreLayout(128);
            assertThat(layout.vectorOffset(3)).isEqualTo(3L * 128 * 4);
        }

        @Test @DisplayName("elementOffset calculates correctly")
        void elementOffset() {
            var layout = new VectorStoreLayout(128);
            // Element 5 of vector 2
            long expected = 2L * 128 * 4 + 5L * 4;
            assertThat(layout.elementOffset(2, 5)).isEqualTo(expected);
        }

        @Test @DisplayName("totalByteSize for N vectors")
        void totalByteSize() {
            var layout = new VectorStoreLayout(384);
            assertThat(layout.totalByteSize(100)).isEqualTo(100L * 384 * 4);
        }

        @Test @DisplayName("writeVector and readVector roundtrip")
        void writeReadRoundtrip() {
            var layout = new VectorStoreLayout(4);
            try (Arena arena = Arena.ofConfined()) {
                var segment = arena.allocate(layout.totalByteSize(3));
                float[] vec = {1.0f, 2.0f, 3.0f, 4.0f};
                layout.writeVector(segment, 1, vec);
                float[] read = layout.readVector(segment, 1);
                assertThat(read).containsExactly(1.0f, 2.0f, 3.0f, 4.0f);
            }
        }

        @Test @DisplayName("writeVector rejects wrong dimension")
        void writeWrongDimension() {
            var layout = new VectorStoreLayout(4);
            try (Arena arena = Arena.ofConfined()) {
                var segment = arena.allocate(layout.totalByteSize(1));
                assertThatThrownBy(() -> layout.writeVector(segment, 0, new float[]{1f, 2f}))
                        .isInstanceOf(SpectorValidationException.class);
            }
        }

        @Test @DisplayName("readVector into existing buffer")
        void readVectorIntoBuffer() {
            var layout = new VectorStoreLayout(3);
            try (Arena arena = Arena.ofConfined()) {
                var segment = arena.allocate(layout.totalByteSize(1));
                layout.writeVector(segment, 0, new float[]{5f, 6f, 7f});
                float[] dst = new float[5];
                layout.readVector(segment, 0, dst, 2);
                assertThat(dst[2]).isEqualTo(5f);
                assertThat(dst[3]).isEqualTo(6f);
                assertThat(dst[4]).isEqualTo(7f);
            }
        }

        @Test @DisplayName("multiple vectors stored independently")
        void multipleVectors() {
            var layout = new VectorStoreLayout(2);
            try (Arena arena = Arena.ofConfined()) {
                var segment = arena.allocate(layout.totalByteSize(3));
                layout.writeVector(segment, 0, new float[]{1f, 2f});
                layout.writeVector(segment, 1, new float[]{3f, 4f});
                layout.writeVector(segment, 2, new float[]{5f, 6f});

                assertThat(layout.readVector(segment, 0)).containsExactly(1f, 2f);
                assertThat(layout.readVector(segment, 1)).containsExactly(3f, 4f);
                assertThat(layout.readVector(segment, 2)).containsExactly(5f, 6f);
            }
        }
    }
}
