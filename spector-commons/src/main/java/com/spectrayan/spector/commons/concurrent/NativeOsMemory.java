package com.spectrayan.spector.commons.concurrent;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for executing low-level native OS page cache operations using Java Panama FFM API.
 * Primarily binds POSIX madvise(2) on Linux/Unix systems with safe cross-platform no-ops on Windows.
 */
public final class NativeOsMemory {

    private static final Logger log = LoggerFactory.getLogger(NativeOsMemory.class);

    // POSIX madvise constants
    public static final int MADV_NORMAL = 0;
    public static final int MADV_RANDOM = 1;
    public static final int MADV_SEQUENTIAL = 2;
    public static final int MADV_WILLNEED = 3;
    public static final int MADV_DONTNEED = 4;

    private static final MethodHandle MADVISE_HANDLE;

    static {
        Linker linker = Linker.nativeLinker();
        MethodHandle madvise = null;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            try {
                // POSIX default symbols from standard libc
                SymbolLookup libc = linker.defaultLookup();
                madvise = libc.find("madvise").map(addr -> linker.downcallHandle(
                    addr,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)
                )).orElse(null);

                if (madvise != null) {
                    log.info("NativeOsMemory: Successfully bound POSIX madvise(2)");
                } else {
                    log.warn("NativeOsMemory: POSIX madvise(2) symbol not found in standard libc");
                }
            } catch (Exception e) {
                log.warn("NativeOsMemory: Failed to bind POSIX madvise(2)", e);
            }
        } else {
            log.debug("NativeOsMemory: Running on Windows. Dynamic OS paging optimizations will use safe no-ops.");
        }

        MADVISE_HANDLE = madvise;
    }

    private NativeOsMemory() {}

    /**
     * Executes the POSIX madvise(2) system call on a mapped memory segment.
     *
     * @param segment the memory-mapped segment
     * @param advice  the advice constant (e.g. MADV_WILLNEED, MADV_DONTNEED)
     * @return true if successful or if operating on a platform where madvise is a safe no-op; false on error
     */
    public static boolean advise(MemorySegment segment, int advice) {
        if (segment == null || !segment.isMapped()) {
            return false;
        }

        if (MADVISE_HANDLE == null) {
            log.trace("NativeOsMemory: Paging advice {} requested, but native handle is unavailable (safe no-op).", advice);
            return true; 
        }

        try {
            int result = (int) MADVISE_HANDLE.invokeExact(segment.address(), segment.byteSize(), advice);
            if (result == 0) {
                log.debug("madvise: Successfully applied advice {} on segment at address {} ({} bytes)", advice, segment.address(), segment.byteSize());
                return true;
            } else {
                log.warn("madvise: Failed with code {} for advice {} on segment at address {}", result, advice, segment.address());
                return false;
            }
        } catch (Throwable t) {
            log.warn("NativeOsMemory: Failed to invoke native madvise: {}", t.getMessage());
            return false;
        }
    }
}
