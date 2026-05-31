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

import java.lang.foreign.Arena;
import java.time.Instant;

/**
 * Represents a single GPU device memory allocation tracked by {@link GpuMemoryManager}.
 *
 * @param devicePointer the CUDA device pointer for this allocation
 * @param sizeBytes     size of the allocation in bytes
 * @param arena         the Arena scope that owns this allocation's lifetime
 * @param allocatedAt   timestamp when this allocation was made
 */
public record GpuAllocation(
        long devicePointer,
        long sizeBytes,
        Arena arena,
        Instant allocatedAt
) {}
