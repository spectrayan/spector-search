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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests DocumentStore binary save/load round-trip.
 */
class DocumentStorePersistenceTest {

    @TempDir
    Path tmpDir;

    @Test
    void saveAndLoad_preservesAllDocumentFields() {
        Path file = tmpDir.resolve("documents.dat");

        // Create and populate
        DocumentStore original = new DocumentStore();
        original.put(new Document("doc-1", "Title One", "Content of document one",
                Map.of("author", "Alice", "version", "1.0")));
        original.put(new Document("doc-2", "Title Two", "Content of document two",
                Map.of()));
        original.put(Document.of("doc-3", "Simple content only"));

        // Save
        original.save(file);

        // Load
        DocumentStore loaded = DocumentStore.load(file);

        // Verify
        assertThat(loaded.size()).isEqualTo(3);

        Document d1 = loaded.get("doc-1");
        assertThat(d1).isNotNull();
        assertThat(d1.title()).isEqualTo("Title One");
        assertThat(d1.content()).isEqualTo("Content of document one");
        assertThat(d1.metadata()).containsEntry("author", "Alice");
        assertThat(d1.metadata()).containsEntry("version", "1.0");

        Document d2 = loaded.get("doc-2");
        assertThat(d2).isNotNull();
        assertThat(d2.title()).isEqualTo("Title Two");
        assertThat(d2.metadata()).isEmpty();

        Document d3 = loaded.get("doc-3");
        assertThat(d3).isNotNull();
        assertThat(d3.content()).isEqualTo("Simple content only");
    }

    @Test
    void load_missingFile_returnsEmptyStore() {
        DocumentStore loaded = DocumentStore.load(tmpDir.resolve("nonexistent.dat"));
        assertThat(loaded.size()).isEqualTo(0);
    }

    @Test
    void load_nullPath_returnsEmptyStore() {
        DocumentStore loaded = DocumentStore.load(null);
        assertThat(loaded.size()).isEqualTo(0);
    }

    @Test
    void saveAndLoad_unicodeContent() {
        Path file = tmpDir.resolve("docs_unicode.dat");

        DocumentStore original = new DocumentStore();
        original.put(new Document("uni-1", "日本語タイトル", "这是中文内容 🎉",
                Map.of("language", "multi")));

        original.save(file);
        DocumentStore loaded = DocumentStore.load(file);

        assertThat(loaded.size()).isEqualTo(1);
        Document d = loaded.get("uni-1");
        assertThat(d.title()).isEqualTo("日本語タイトル");
        assertThat(d.content()).isEqualTo("这是中文内容 🎉");
    }

    @Test
    void saveAndLoad_emptyStore() {
        Path file = tmpDir.resolve("docs_empty.dat");

        DocumentStore original = new DocumentStore();
        original.save(file);

        DocumentStore loaded = DocumentStore.load(file);
        assertThat(loaded.size()).isEqualTo(0);
    }
}
