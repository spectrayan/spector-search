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
package com.spectrayan.spector.commons;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tests for {@link StreamingChunker}.
 */
class StreamingChunkerTest {

    @Test
    void chunkFromReader() {
        String text = "First sentence here. Second sentence here. Third sentence here. " +
                "Fourth sentence here. Fifth sentence here.";
        Reader reader = new StringReader(text);

        List<TextChunker.Chunk> chunks = new ArrayList<>();
        Iterator<TextChunker.Chunk> iter = StreamingChunker.chunkIterator(reader, "doc", 40, 10);
        while (iter.hasNext()) chunks.add(iter.next());

        assertThat(chunks).hasSizeGreaterThan(1);
        for (var chunk : chunks) {
            assertThat(chunk.parentId()).isEqualTo("doc");
            assertThat(chunk.chunkId()).startsWith("doc::chunk-");
        }
    }

    @Test
    void chunkFromFile(@TempDir Path tempDir) throws IOException {
        // Write a large-ish file
        Path file = tempDir.resolve("test.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            content.append("This is sentence number ").append(i).append(". ");
        }
        Files.writeString(file, content.toString());

        List<TextChunker.Chunk> chunks = new ArrayList<>();
        try (Stream<TextChunker.Chunk> stream = StreamingChunker.chunkFile(file, "file-doc", 200, 40)) {
            stream.forEach(chunks::add);
        }

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().chunkId()).isEqualTo("file-doc::chunk-0");

        // Verify chunk start positions are advancing
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startChar())
                    .as("chunk %d should start after chunk %d", i, i - 1)
                    .isGreaterThan(chunks.get(i - 1).startChar());
        }
    }

    @Test
    void chunkFromInputStream() {
        String text = "Streaming text from an input stream. " +
                "This is useful for network sources. " +
                "And for large files that cannot fit in memory.";
        InputStream is = new ByteArrayInputStream(text.getBytes());

        List<TextChunker.Chunk> chunks;
        try (Stream<TextChunker.Chunk> stream = StreamingChunker.chunkStream(is, "stream-doc", 50, 10)) {
            chunks = stream.toList();
        }

        assertThat(chunks).isNotEmpty();
        for (var chunk : chunks) {
            assertThat(chunk.parentId()).isEqualTo("stream-doc");
            assertThat(chunk.text()).isNotBlank();
        }
    }

    @Test
    void shortContentProducesSingleChunk() {
        Reader reader = new StringReader("Short text.");
        List<TextChunker.Chunk> chunks = new ArrayList<>();
        var iter = StreamingChunker.chunkIterator(reader, "doc", 200, 20);
        while (iter.hasNext()) chunks.add(iter.next());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo("Short text.");
    }

    @Test
    void emptyReaderProducesNoChunks() {
        Reader reader = new StringReader("");
        var iter = StreamingChunker.chunkIterator(reader, "doc", 100, 10);
        assertThat(iter.hasNext()).isFalse();
    }

    @Test
    void chunksHaveCorrectGlobalOffsets(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("offsets.txt");
        String content = "AAAA. BBBB. CCCC. DDDD. EEEE. FFFF. GGGG. HHHH. ";
        Files.writeString(file, content);

        List<TextChunker.Chunk> chunks;
        try (Stream<TextChunker.Chunk> stream = StreamingChunker.chunkFile(file, "doc", 20, 5)) {
            chunks = stream.toList();
        }

        assertThat(chunks).hasSizeGreaterThan(1);
        // First chunk should start at offset 0
        assertThat(chunks.getFirst().startChar()).isEqualTo(0);
    }

    @Test
    void largeFileBoundedMemory(@TempDir Path tempDir) throws IOException {
        // Create a 100K file
        Path file = tempDir.resolve("large.txt");
        try (Writer w = Files.newBufferedWriter(file)) {
            for (int i = 0; i < 10_000; i++) {
                w.write("This is sentence " + i + " in a very large file. ");
            }
        }

        long fileSize = Files.size(file);
        assertThat(fileSize).isGreaterThan(100_000);

        // Stream with small chunk size — proves we don't OOM
        List<TextChunker.Chunk> chunks;
        try (Stream<TextChunker.Chunk> stream = StreamingChunker.chunkFile(file, "big", 500, 50)) {
            chunks = stream.toList();
        }

        assertThat(chunks).hasSizeGreaterThan(10);
        // Each chunk should be reasonable size
        for (var c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(600); // chunkSize + tolerance
        }
    }
}
