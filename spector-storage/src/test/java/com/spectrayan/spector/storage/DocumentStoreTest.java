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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DocumentStore} and {@link Document}.
 */
class DocumentStoreTest {

    @Test
    void putAndGet() {
        var store = new DocumentStore();
        var doc = Document.of("d1", "Hello World");
        store.put(doc);

        assertThat(store.get("d1")).isEqualTo(doc);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void getNonexistent() {
        var store = new DocumentStore();
        assertThat(store.get("nope")).isNull();
    }

    @Test
    void contains() {
        var store = new DocumentStore();
        store.put(Document.of("d1", "text"));
        assertThat(store.contains("d1")).isTrue();
        assertThat(store.contains("d2")).isFalse();
    }

    @Test
    void remove() {
        var store = new DocumentStore();
        store.put(Document.of("d1", "text"));
        var removed = store.remove("d1");
        assertThat(removed).isNotNull();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void updateReplacesExisting() {
        var store = new DocumentStore();
        store.put(Document.of("d1", "old"));
        store.put(Document.of("d1", "new"));
        assertThat(store.get("d1").content()).isEqualTo("new");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void documentWithMetadata() {
        var doc = new Document("d1", "Title", "Content",
                Map.of("author", "test", "year", 2026));
        assertThat(doc.metadata()).containsEntry("author", "test");
        assertThat(doc.title()).isEqualTo("Title");
    }

    @Test
    void documentFactoryMethods() {
        var d1 = Document.of("id", "content");
        assertThat(d1.title()).isEmpty();
        assertThat(d1.metadata()).isEmpty();

        var d2 = Document.of("id", "title", "content");
        assertThat(d2.title()).isEqualTo("title");
    }

    @Test
    void closeClearsStore() {
        var store = new DocumentStore();
        store.put(Document.of("d1", "text"));
        store.close();
        assertThat(store.size()).isEqualTo(0);
    }
}
