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

class NativeOsMemoryTest {

    @Test
    void nullSegmentReturnsFalse() {
        assertThat(NativeOsMemory.advise(null, NativeOsMemory.MADV_WILLNEED)).isFalse();
    }

    @Test
    void nonMappedSegmentReturnsFalse() {
        MemorySegment heapSegment = MemorySegment.ofArray(new byte[1024]);
        assertThat(NativeOsMemory.advise(heapSegment, NativeOsMemory.MADV_WILLNEED)).isFalse();
    }

    @Test
    void mappedSegmentAdvice(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test_advice.dat");
        Files.write(file, new byte[1024]);

        try (var raf = new RandomAccessFile(file.toFile(), "rw");
             var channel = raf.getChannel();
             var arena = Arena.ofConfined()) {
            
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, 1024, arena);
            
            // Apply advice. It must work cleanly (either returning true POSIX status or gracefully degenerating to safe Windows no-op)
            // and MUST NEVER throw exceptions.
            boolean success = NativeOsMemory.advise(mappedSegment, NativeOsMemory.MADV_WILLNEED);
            assertThat(success).isTrue();

            boolean successDontNeed = NativeOsMemory.advise(mappedSegment, NativeOsMemory.MADV_DONTNEED);
            assertThat(successDontNeed).isTrue();
        }
    }
}
