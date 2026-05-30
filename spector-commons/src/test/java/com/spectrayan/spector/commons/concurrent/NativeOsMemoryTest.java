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
            assertThat(mappedSegment.isMapped()).isTrue();

            // The core contract: advise() MUST NEVER throw exceptions, even if the
            // underlying madvise(2) syscall is unavailable or fails (e.g. in sandboxed
            // CI containers). On Windows it returns true (safe no-op), on Linux it
            // returns true if madvise succeeds or false if the kernel rejects it.
            // We do NOT assert the return value since it depends on OS/container config.
            NativeOsMemory.advise(mappedSegment, NativeOsMemory.MADV_WILLNEED);
            NativeOsMemory.advise(mappedSegment, NativeOsMemory.MADV_DONTNEED);
        }
    }
}
