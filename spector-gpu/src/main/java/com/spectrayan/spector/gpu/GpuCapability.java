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
package com.spectrayan.spector.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects and reports GPU/CUDA capability at runtime via Panama FFM.
 *
 * <p>Attempts to load the CUDA driver library (nvcuda.dll on Windows,
 * libcuda.so on Linux) and query device properties. If CUDA is not
 * available, the engine gracefully falls back to CPU SIMD.</p>
 *
 * <h3>Detection Strategy</h3>
 * <ol>
 *   <li>Load CUDA driver shared library via {@link SymbolLookup}</li>
 *   <li>Call {@code cuInit(0)} to initialize the driver</li>
 *   <li>Call {@code cuDeviceGetCount} to find available GPUs</li>
 *   <li>Call {@code cuDeviceGetName} to retrieve device name</li>
 * </ol>
 */
public final class GpuCapability {

    private static final Logger log = LoggerFactory.getLogger(GpuCapability.class);

    private static volatile GpuInfo cachedInfo;

    /** Immutable GPU detection result. */
    public record GpuInfo(
            boolean available,
            int deviceCount,
            String deviceName,
            long totalMemoryBytes,
            int computeMajor,
            int computeMinor,
            String errorMessage
    ) {
        public static GpuInfo unavailable(String reason) {
            return new GpuInfo(false, 0, "none", 0, 0, 0, reason);
        }

        public static GpuInfo available(int deviceCount, String name, long memory,
                                         int major, int minor) {
            return new GpuInfo(true, deviceCount, name, memory, major, minor, null);
        }

        /** Human-readable summary. */
        public String report() {
            if (!available) return "GPU: unavailable (" + errorMessage + ")";
            return "GPU: %s, %d MB, compute %d.%d, %d device(s)".formatted(
                    deviceName, totalMemoryBytes / (1024 * 1024), computeMajor, computeMinor, deviceCount);
        }
    }

    private GpuCapability() {}

    /**
     * Detects CUDA GPU availability. Results are cached after first call.
     *
     * @return GPU capability info
     */
    public static GpuInfo detect() {
        if (cachedInfo != null) return cachedInfo;
        synchronized (GpuCapability.class) {
            if (cachedInfo != null) return cachedInfo;
            cachedInfo = doDetect();
            log.info(cachedInfo.report());
            return cachedInfo;
        }
    }

    /** Returns true if a CUDA GPU is available. */
    public static boolean isAvailable() {
        return detect().available();
    }

    private static GpuInfo doDetect() {
        try {
            // Attempt to load CUDA driver library
            String libName = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "nvcuda" : "cuda";

            SymbolLookup cudaLib;
            try {
                cudaLib = SymbolLookup.libraryLookup(libName, Arena.global());
            } catch (IllegalArgumentException e) {
                return GpuInfo.unavailable("CUDA driver library not found: " + libName);
            }

            Linker linker = Linker.nativeLinker();

            // cuInit(0)
            MethodHandle cuInit = linker.downcallHandle(
                    cudaLib.find("cuInit").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int initResult = (int) cuInit.invoke(0);
            if (initResult != 0) {
                return GpuInfo.unavailable("cuInit failed: error " + initResult);
            }

            // cuDeviceGetCount(&count)
            try (var arena = Arena.ofConfined()) {
                MemorySegment countPtr = arena.allocate(ValueLayout.JAVA_INT);
                MethodHandle cuDeviceGetCount = linker.downcallHandle(
                        cudaLib.find("cuDeviceGetCount").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                int countResult = (int) cuDeviceGetCount.invoke(countPtr);
                if (countResult != 0) {
                    return GpuInfo.unavailable("cuDeviceGetCount failed: error " + countResult);
                }
                int deviceCount = countPtr.get(ValueLayout.JAVA_INT, 0);
                if (deviceCount == 0) {
                    return GpuInfo.unavailable("No CUDA devices found");
                }

                // cuDeviceGet(&device, 0)
                MemorySegment devicePtr = arena.allocate(ValueLayout.JAVA_INT);
                MethodHandle cuDeviceGet = linker.downcallHandle(
                        cudaLib.find("cuDeviceGet").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                cuDeviceGet.invoke(devicePtr, 0);
                int device = devicePtr.get(ValueLayout.JAVA_INT, 0);

                // cuDeviceGetName(name, 256, device)
                MemorySegment nameBuffer = arena.allocate(256);
                MethodHandle cuDeviceGetName = linker.downcallHandle(
                        cudaLib.find("cuDeviceGetName").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                cuDeviceGetName.invoke(nameBuffer, 256, device);
                String deviceName = nameBuffer.getString(0);

                // cuDeviceTotalMem(&bytes, device)
                MemorySegment memPtr = arena.allocate(ValueLayout.JAVA_LONG);
                MethodHandle cuDeviceTotalMem = linker.downcallHandle(
                        cudaLib.find("cuDeviceTotalMem_v2").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                cuDeviceTotalMem.invoke(memPtr, device);
                long totalMem = memPtr.get(ValueLayout.JAVA_LONG, 0);

                // cuDeviceGetAttribute(&value, attrib, device)
                MethodHandle cuDeviceGetAttribute = linker.downcallHandle(
                        cudaLib.find("cuDeviceGetAttribute").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                MemorySegment attrPtr = arena.allocate(ValueLayout.JAVA_INT);

                // CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR = 75
                cuDeviceGetAttribute.invoke(attrPtr, 75, device);
                int computeMajor = attrPtr.get(ValueLayout.JAVA_INT, 0);

                // CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR = 76
                cuDeviceGetAttribute.invoke(attrPtr, 76, device);
                int computeMinor = attrPtr.get(ValueLayout.JAVA_INT, 0);

                return GpuInfo.available(deviceCount, deviceName, totalMem,
                        computeMajor, computeMinor);
            }

        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            return GpuInfo.unavailable("CUDA driver not installed: " + e.getMessage());
        } catch (Throwable e) {
            return GpuInfo.unavailable("GPU detection error: " + e.getMessage());
        }
    }
}
