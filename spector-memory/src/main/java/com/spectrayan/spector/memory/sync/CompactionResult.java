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
package com.spectrayan.spector.memory.sync;

import com.spectrayan.spector.memory.model.MemoryType;

/**
 * Result of a vacuum/compaction operation on a tier store.
 *
 * @param tier               the memory tier that was compacted
 * @param beforeCount        total records before compaction (live + tombstoned)
 * @param afterCount         live records after compaction
 * @param tombstonesRemoved  number of tombstoned records removed
 * @param bytesReclaimed     bytes freed by removing tombstoned records
 * @param durationMs         compaction duration in milliseconds
 */
public record CompactionResult(
    MemoryType tier,
    int beforeCount,
    int afterCount,
    int tombstonesRemoved,
    long bytesReclaimed,
    long durationMs
) {}
