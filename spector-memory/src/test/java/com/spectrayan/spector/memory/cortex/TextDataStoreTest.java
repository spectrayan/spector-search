/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.StorageLayout;
import com.spectrayan.spector.memory.cortex.TextDataStore.TextEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TextDataStore} — binary text.dat reader/writer.
 */
class TextDataStoreTest {

    @TempDir
    Path tempDir;

    private TextDataStore store;

    @BeforeEach
    void setUp() {
        store = new TextDataStore(tempDir.resolve(StorageLayout.FILE_TEXT));
    }

    @Test
    void write_and_readAll_roundtrip() {
        store.write("mem-001", MemoryType.SEMANTIC, "The quick brown fox jumps over the lazy dog");
        store.write("mem-002", MemoryType.EPISODIC, "User asked about deployment pipeline");
        store.write("mem-003", MemoryType.PROCEDURAL, "Always run tests before pushing to main");

        Map<String, TextEntry> entries = store.readAll();

        assertThat(entries).hasSize(3);
        assertThat(entries.get("mem-001").text()).isEqualTo("The quick brown fox jumps over the lazy dog");
        assertThat(entries.get("mem-001").tier()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(entries.get("mem-002").tier()).isEqualTo(MemoryType.EPISODIC);
        assertThat(entries.get("mem-003").tier()).isEqualTo(MemoryType.PROCEDURAL);
    }

    @Test
    void empty_file_returns_empty_map() {
        Map<String, TextEntry> entries = store.readAll();
        assertThat(entries).isEmpty();
    }

    @Test
    void large_text_roundtrip() {
        // 10K characters
        String largeText = "A".repeat(10_000);
        store.write("large-001", MemoryType.SEMANTIC, largeText);

        Map<String, TextEntry> entries = store.readAll();

        assertThat(entries).hasSize(1);
        assertThat(entries.get("large-001").text()).hasSize(10_000);
        assertThat(entries.get("large-001").text()).isEqualTo(largeText);
    }

    @Test
    void unicode_roundtrip() {
        String unicodeText = "日本語テスト 🧠 émojis and Ü̈nicode";
        store.write("unicode-001", MemoryType.SEMANTIC, unicodeText);

        Map<String, TextEntry> entries = store.readAll();

        assertThat(entries).hasSize(1);
        assertThat(entries.get("unicode-001").text()).isEqualTo(unicodeText);
    }

    @Test
    void many_entries_roundtrip() {
        int count = 500;
        for (int i = 0; i < count; i++) {
            store.write("batch-" + i, MemoryType.SEMANTIC, "Content for entry " + i);
        }

        Map<String, TextEntry> entries = store.readAll();
        assertThat(entries).hasSize(count);

        for (int i = 0; i < count; i++) {
            assertThat(entries.get("batch-" + i).text()).isEqualTo("Content for entry " + i);
        }
    }

    @Test
    void rebuild_compacts_entries() {
        store.write("mem-001", MemoryType.SEMANTIC, "Original text 1");
        store.write("mem-002", MemoryType.EPISODIC, "Original text 2");
        store.write("mem-003", MemoryType.SEMANTIC, "Original text 3");

        // Simulate compaction: remove mem-002
        Map<String, TextEntry> remaining = store.readAll();
        remaining.remove("mem-002");

        store.rebuild(remaining);

        Map<String, TextEntry> afterRebuild = store.readAll();
        assertThat(afterRebuild).hasSize(2);
        assertThat(afterRebuild).containsKey("mem-001");
        assertThat(afterRebuild).containsKey("mem-003");
        assertThat(afterRebuild).doesNotContainKey("mem-002");
    }

    @Test
    void forPartition_creates_store_with_correct_path() {
        Path partitionDir = tempDir.resolve("partitions").resolve("000_1717430400");
        TextDataStore partitionStore = TextDataStore.forPartition(partitionDir);
        assertThat(partitionStore.path()).isEqualTo(partitionDir.resolve(StorageLayout.FILE_TEXT));
    }

    @Test
    void size_tracks_entry_count() {
        assertThat(store.size()).isZero();

        store.write("mem-001", MemoryType.SEMANTIC, "Text 1");
        assertThat(store.size()).isEqualTo(1);

        store.write("mem-002", MemoryType.EPISODIC, "Text 2");
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void all_memory_types_preserved() {
        for (MemoryType type : MemoryType.values()) {
            store.write("type-" + type.name(), type, "Content for " + type.name());
        }

        Map<String, TextEntry> entries = store.readAll();
        assertThat(entries).hasSize(MemoryType.values().length);

        for (MemoryType type : MemoryType.values()) {
            assertThat(entries.get("type-" + type.name()).tier()).isEqualTo(type);
        }
    }
}
