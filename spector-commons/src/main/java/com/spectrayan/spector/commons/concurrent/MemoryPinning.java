package com.spectrayan.spector.commons.concurrent;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for pinning memory-mapped memory segments to physical RAM
 * to prevent cold starts, page cache thrashing, and OS swapping.
 * Uses native OS-level mlock/munlock on Linux/POSIX and VirtualLock/VirtualUnlock on Windows.
 */
public final class MemoryPinning {

    private static final Logger log = LoggerFactory.getLogger(MemoryPinning.class);

    private static final MethodHandle LOCK_HANDLE;
    private static final MethodHandle UNLOCK_HANDLE;
    private static final boolean WINDOWS;

    private static final java.util.concurrent.atomic.AtomicLong pinnedBytes = new java.util.concurrent.atomic.AtomicLong(0);

    static {
        Linker linker = Linker.nativeLinker();
        MethodHandle lock = null;
        MethodHandle unlock = null;
        boolean isWin = false;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            isWin = true;
            try {
                // Windows kernel32 symbols
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                lock = kernel32.find("VirtualLock").map(addr -> linker.downcallHandle(
                    addr,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                )).orElse(null);

                unlock = kernel32.find("VirtualUnlock").map(addr -> linker.downcallHandle(
                    addr,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                )).orElse(null);

                if (lock != null && unlock != null) {
                    log.info("MemoryPinning: Successfully bound Windows VirtualLock/VirtualUnlock");
                }
            } catch (Exception e) {
                log.warn("MemoryPinning: Failed to load Windows kernel32 symbols", e);
            }
        } else {
            // Linux/macOS/POSIX symbols from standard libc
            try {
                SymbolLookup libc = linker.defaultLookup();
                lock = libc.find("mlock").map(addr -> linker.downcallHandle(
                    addr,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                )).orElse(null);

                unlock = libc.find("munlock").map(addr -> linker.downcallHandle(
                    addr,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                )).orElse(null);

                if (lock != null && unlock != null) {
                    log.info("MemoryPinning: Successfully bound POSIX mlock/munlock");
                }
            } catch (Exception e) {
                log.warn("MemoryPinning: Failed to load POSIX symbols from libc", e);
            }
        }

        LOCK_HANDLE = lock;
        UNLOCK_HANDLE = unlock;
        WINDOWS = isWin;
    }

    private MemoryPinning() {}

    /**
     * Returns the total off-heap memory bytes successfully pinned in physical RAM.
     */
    public static long pinnedBytes() {
        return pinnedBytes.get();
    }

    /**
     * Pins the memory segment to physical RAM, preventing it from being swapped or paged out.
     *
     * @param segment the memory segment to lock
     * @return true if successfully locked, false otherwise
     */
    public static boolean lock(MemorySegment segment) {
        if (segment == null || !segment.isMapped()) {
            return false;
        }

        if (LOCK_HANDLE == null) {
            log.warn("MemoryPinning: Lock handle is not available on this platform.");
            return false;
        }

        try {
            int result = (int) LOCK_HANDLE.invokeExact(segment.address(), segment.byteSize());
            if (WINDOWS) {
                // Windows VirtualLock returns non-zero (true) on success
                if (result != 0) {
                    log.debug("VirtualLock: Locked off-heap segment at address {} ({} bytes)", segment.address(), segment.byteSize());
                    pinnedBytes.addAndGet(segment.byteSize());
                    return true;
                } else {
                    log.warn("VirtualLock failed. Ensure working set size limits are sufficient on Windows.");
                }
            } else {
                // POSIX mlock returns 0 on success
                if (result == 0) {
                    log.debug("mlock: Locked off-heap segment at address {} ({} bytes)", segment.address(), segment.byteSize());
                    pinnedBytes.addAndGet(segment.byteSize());
                    return true;
                } else {
                    log.warn("mlock failed with return code {}. Ensure sufficient locked memory limits (ulimit -l / CAP_SYS_RESOURCE).", result);
                }
            }
        } catch (Throwable t) {
            log.warn("MemoryPinning: Failed to pin memory segment: {}", t.getMessage());
        }
        return false;
    }

    /**
     * Unlocks the memory segment, allowing the operating system to page or swap it out.
     *
     * @param segment the memory segment to unlock
     * @return true if successfully unlocked, false otherwise
     */
    public static boolean unlock(MemorySegment segment) {
        if (segment == null || !segment.isMapped()) {
            return false;
        }

        if (UNLOCK_HANDLE == null) {
            return false;
        }

        try {
            int result = (int) UNLOCK_HANDLE.invokeExact(segment.address(), segment.byteSize());
            if (WINDOWS) {
                if (result != 0) {
                    log.debug("VirtualUnlock: Unlocked off-heap segment at address {}", segment.address());
                    pinnedBytes.addAndGet(-segment.byteSize());
                    return true;
                }
            } else {
                if (result == 0) {
                    log.debug("munlock: Unlocked off-heap segment at address {}", segment.address());
                    pinnedBytes.addAndGet(-segment.byteSize());
                    return true;
                }
            }
        } catch (Throwable t) {
            log.warn("MemoryPinning: Failed to unpin memory segment: {}", t.getMessage());
        }
        return false;
    }
}
