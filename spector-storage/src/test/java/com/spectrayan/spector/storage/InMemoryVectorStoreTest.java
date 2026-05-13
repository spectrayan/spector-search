package com.spectrayan.spector.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link InMemoryVectorStore}.
 */
class InMemoryVectorStoreTest {

    @Test
    void putAndGet() {
        try (var store = new InMemoryVectorStore(3, 100)) {
            float[] v = {1f, 2f, 3f};
            store.put("doc-1", v);

            float[] result = store.get("doc-1");
            assertThat(result).containsExactly(1f, 2f, 3f);
        }
    }

    @Test
    void getByIndex() {
        try (var store = new InMemoryVectorStore(3, 100)) {
            float[] v = {4f, 5f, 6f};
            int index = store.put("doc-1", v);

            float[] result = store.getByIndex(index);
            assertThat(result).containsExactly(4f, 5f, 6f);
        }
    }

    @Test
    void getByIndexIntoDstBuffer() {
        try (var store = new InMemoryVectorStore(3, 100)) {
            store.put("doc-1", new float[]{7f, 8f, 9f});
            float[] dst = new float[5];
            store.getByIndex(0, dst, 1);
            assertThat(dst).containsExactly(0f, 7f, 8f, 9f, 0f);
        }
    }

    @Test
    void indexOf() {
        try (var store = new InMemoryVectorStore(3, 100)) {
            assertThat(store.indexOf("missing")).isEqualTo(-1);
            store.put("doc-1", new float[]{1f, 2f, 3f});
            assertThat(store.indexOf("doc-1")).isEqualTo(0);
        }
    }

    @Test
    void updateInPlace() {
        try (var store = new InMemoryVectorStore(3, 100)) {
            store.put("doc-1", new float[]{1f, 2f, 3f});
            store.put("doc-1", new float[]{10f, 20f, 30f});

            assertThat(store.size()).isEqualTo(1);
            assertThat(store.get("doc-1")).containsExactly(10f, 20f, 30f);
        }
    }

    @Test
    void sizeAndCapacity() {
        try (var store = new InMemoryVectorStore(3, 50)) {
            assertThat(store.size()).isEqualTo(0);
            assertThat(store.capacity()).isEqualTo(50);
            assertThat(store.dimensions()).isEqualTo(3);

            store.put("a", new float[]{1f, 2f, 3f});
            store.put("b", new float[]{4f, 5f, 6f});
            assertThat(store.size()).isEqualTo(2);
        }
    }

    @Test
    void getNonexistentReturnsNull() {
        try (var store = new InMemoryVectorStore(3, 10)) {
            assertThat(store.get("nope")).isNull();
        }
    }

    @Test
    void wrongDimensionsThrows() {
        try (var store = new InMemoryVectorStore(3, 10)) {
            assertThatThrownBy(() -> store.put("x", new float[]{1f, 2f}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("3");
        }
    }

    @Test
    void fullStoreThrows() {
        try (var store = new InMemoryVectorStore(2, 2)) {
            store.put("a", new float[]{1f, 2f});
            store.put("b", new float[]{3f, 4f});
            assertThatThrownBy(() -> store.put("c", new float[]{5f, 6f}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("full");
        }
    }

    @Test
    void closedStoreThrows() {
        var store = new InMemoryVectorStore(3, 10);
        store.put("a", new float[]{1f, 2f, 3f});
        store.close();

        assertThat(store.isClosed()).isTrue();
        assertThatThrownBy(() -> store.get("a"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 128, 384, 768, 1536})
    void roundTripAcrossDimensions(int dim) {
        try (var store = new InMemoryVectorStore(dim, 10)) {
            float[] v = randomVector(dim, 42);
            store.put("test", v);

            float[] result = store.get("test");
            assertThat(result).containsExactly(v);
        }
    }

    @Test
    void multipleVectorsStoreCorrectly() {
        try (var store = new InMemoryVectorStore(3, 1000)) {
            for (int i = 0; i < 100; i++) {
                store.put("doc-" + i, new float[]{i, i + 1f, i + 2f});
            }
            assertThat(store.size()).isEqualTo(100);

            for (int i = 0; i < 100; i++) {
                float[] v = store.get("doc-" + i);
                assertThat(v[0]).isCloseTo(i, within(1e-6f));
            }
        }
    }

    private static float[] randomVector(int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2f - 1f;
        return v;
    }
}
