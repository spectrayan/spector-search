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

/**
 * Metrics exposed by {@link PanamaMemoryDetector} via the monitoring API.
 *
 * @param totalSegments           total number of currently tracked segments
 * @param totalBytes              total bytes across all tracked segments
 * @param thresholdExceedingCount number of segments that have exceeded the lifetime threshold
 * @param untrackedSegmentCount   number of segments that could not be tracked (hook attachment failed)
 */
public record AllocationMetrics(
        int totalSegments,
        long totalBytes,
        int thresholdExceedingCount,
        long untrackedSegmentCount
) {}
