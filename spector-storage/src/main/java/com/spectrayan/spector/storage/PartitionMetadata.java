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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 64-byte metadata header written at the start of each partition file.
 *
 * <h3>Layout</h3>
 * <pre>
 *   Offset  Size  Field
 *   ──────  ────  ─────────────────
 *      0     4B   magic ("VPAR" = 0x56504152)
 *      4     4B   version (1)
 *      8     4B   localCount — live vectors in this partition
 *     12     4B   localCapacity — max vectors this partition can hold
 *     16     4B   dimensions — float elements per vector
 *     20     4B   partitionIndex — 0-based sequence number
 *     24     8B   createdAtMillis — creation timestamp
 *     32    32B   reserved (zeroed)
 * </pre>
 *
 * <p>This header is mmap'd as part of the partition file, so updates to
 * {@code localCount} are written via a single {@link MemorySegment#set}
 * call — no separate I/O needed.</p>
 *
 * @see ShardedMappedVectorStore
 * @deprecated Since V4. Used by {@link ShardedMappedVectorStore} which is being replaced
 * by directory-level partitioning.
 */
@Deprecated(since = "4.0", forRemoval = true)
public final class PartitionMetadata {

    /** Magic bytes: "VPAR" — Vector PARtition. */
    public static final int MAGIC = 0x56504152;

    /** Current metadata format version. */
    public static final int VERSION = 1;

    /** Total size of the metadata header in bytes. */
    public static final int HEADER_BYTES = 64;

    // ── Field offsets ──
    static final long OFF_MAGIC           = 0;
    static final long OFF_VERSION         = 4;
    static final long OFF_LOCAL_COUNT     = 8;
    static final long OFF_LOCAL_CAPACITY  = 12;
    static final long OFF_DIMENSIONS      = 16;
    static final long OFF_PARTITION_INDEX = 20;
    static final long OFF_CREATED_AT      = 24;
    // 32–63: reserved

    private PartitionMetadata() {}

    /**
     * Writes a fresh metadata header to the given segment at offset 0.
     *
     * @param segment        the mmap'd segment (must be ≥ HEADER_BYTES)
     * @param localCapacity  max vectors this partition can hold
     * @param dimensions     vector dimensionality
     * @param partitionIndex 0-based partition sequence number
     */
    public static void writeHeader(MemorySegment segment, int localCapacity,
                                    int dimensions, int partitionIndex) {
        segment.set(ValueLayout.JAVA_INT, OFF_MAGIC, MAGIC);
        segment.set(ValueLayout.JAVA_INT, OFF_VERSION, VERSION);
        segment.set(ValueLayout.JAVA_INT, OFF_LOCAL_COUNT, 0);
        segment.set(ValueLayout.JAVA_INT, OFF_LOCAL_CAPACITY, localCapacity);
        segment.set(ValueLayout.JAVA_INT, OFF_DIMENSIONS, dimensions);
        segment.set(ValueLayout.JAVA_INT, OFF_PARTITION_INDEX, partitionIndex);
        segment.set(ValueLayout.JAVA_LONG, OFF_CREATED_AT, System.currentTimeMillis());
    }

    /**
     * Validates the magic and version fields in a partition header.
     *
     * @param segment the mmap'd segment
     * @return true if the header is valid
     */
    public static boolean isValid(MemorySegment segment) {
        if (segment.byteSize() < HEADER_BYTES) return false;
        int magic = segment.get(ValueLayout.JAVA_INT, OFF_MAGIC);
        int version = segment.get(ValueLayout.JAVA_INT, OFF_VERSION);
        return magic == MAGIC && version == VERSION;
    }

    /** Reads the localCount field from the header. */
    public static int readLocalCount(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_INT, OFF_LOCAL_COUNT);
    }

    /** Writes the localCount field to the header. */
    public static void writeLocalCount(MemorySegment segment, int count) {
        segment.set(ValueLayout.JAVA_INT, OFF_LOCAL_COUNT, count);
    }

    /** Reads the localCapacity field from the header. */
    public static int readLocalCapacity(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_INT, OFF_LOCAL_CAPACITY);
    }

    /** Reads the dimensions field from the header. */
    public static int readDimensions(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_INT, OFF_DIMENSIONS);
    }

    /** Reads the partitionIndex field from the header. */
    public static int readPartitionIndex(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_INT, OFF_PARTITION_INDEX);
    }

    /** Reads the creation timestamp from the header. */
    public static long readCreatedAt(MemorySegment segment) {
        return segment.get(ValueLayout.JAVA_LONG, OFF_CREATED_AT);
    }
}
