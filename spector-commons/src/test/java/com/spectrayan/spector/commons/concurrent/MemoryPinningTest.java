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
package com.spectrayan.spector.commons.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryPinningTest {

    @Test
    void nullSegmentReturnsFalse() {
        assertThat(MemoryPinning.lock(null)).isFalse();
        assertThat(MemoryPinning.unlock(null)).isFalse();
    }

    @Test
    void nonMappedSegmentReturnsFalse() {
        MemorySegment heapSegment = MemorySegment.ofArray(new byte[1024]);
        assertThat(MemoryPinning.lock(heapSegment)).isFalse();
        assertThat(MemoryPinning.unlock(heapSegment)).isFalse();
    }

    @Test
    void mappedSegmentPinning(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test_pinning.dat");
        Files.write(file, new byte[1024]);

        try (var raf = new RandomAccessFile(file.toFile(), "rw");
             var channel = raf.getChannel();
             var arena = Arena.ofConfined()) {
            
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, 1024, arena);
            
            // Try locking. Even if locking fails due to insufficient system/ulimit privileges,
            // the method must handle it gracefully, return false/true, and NEVER throw an exception.
            boolean locked = MemoryPinning.lock(mappedSegment);
            
            // Unlocking should behave identically and securely
            boolean unlocked = MemoryPinning.unlock(mappedSegment);
            
            if (locked) {
                assertThat(unlocked).isTrue();
            }
        }
    }
}
