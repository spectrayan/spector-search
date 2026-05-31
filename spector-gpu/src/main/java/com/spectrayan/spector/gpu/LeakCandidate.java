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

import java.time.Duration;
import java.time.Instant;

/**
 * Represents a potential memory leak — a tracked MemorySegment that has remained
 * allocated beyond the configured lifetime threshold.
 *
 * @param segmentId      unique identifier for the tracked segment
 * @param sizeBytes      size of the allocation in bytes
 * @param allocatedAt    timestamp when the segment was created
 * @param elapsedTime    how long the segment has been alive
 * @param allocationSite stack trace captured at the time of allocation
 */
public record LeakCandidate(
        long segmentId,
        long sizeBytes,
        Instant allocatedAt,
        Duration elapsedTime,
        StackTraceElement[] allocationSite
) {}
