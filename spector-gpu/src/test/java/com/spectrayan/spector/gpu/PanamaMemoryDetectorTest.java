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

import com.spectrayan.spector.commons.error.SpectorValidationException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PanamaMemoryDetector}.
 */
class PanamaMemoryDetectorTest {

    @Test
    void defaultThresholdIs300Seconds() {
        var detector = new PanamaMemoryDetector();
        assertThat(detector.getLifetimeThreshold()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void rejectsThresholdBelowOneSecond() {
        assertThatThrownBy(() -> new PanamaMemoryDetector(Duration.ofMillis(500)))
                .isInstanceOf(SpectorValidationException.class)
                .hasMessageContaining("lifetimeThreshold");
    }

    @Test
    void rejectsNullThreshold() {
        assertThatThrownBy(() -> new PanamaMemoryDetector(null))
                .isInstanceOf(SpectorValidationException.class);
    }

    @Test
    void trackAllocationIncreasesMetrics() {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(10));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(1024);
            detector.trackAllocation(segment, Thread.currentThread().getStackTrace());

            AllocationMetrics metrics = detector.getMetrics();
            assertThat(metrics.totalSegments()).isEqualTo(1);
            assertThat(metrics.totalBytes()).isEqualTo(1024);
            assertThat(metrics.thresholdExceedingCount()).isZero();
            assertThat(metrics.untrackedSegmentCount()).isZero();
        }
    }

    @Test
    void trackDeallocationRemovesFromRegistry() {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(10));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(512);
            detector.trackAllocation(segment, Thread.currentThread().getStackTrace());

            assertThat(detector.getMetrics().totalSegments()).isEqualTo(1);

            detector.trackDeallocation(segment);

            assertThat(detector.getMetrics().totalSegments()).isZero();
            assertThat(detector.getMetrics().totalBytes()).isZero();
        }
    }

    @Test
    void trackingMultipleSegments() {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(10));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg1 = arena.allocate(100);
            MemorySegment seg2 = arena.allocate(200);
            MemorySegment seg3 = arena.allocate(300);

            detector.trackAllocation(seg1, Thread.currentThread().getStackTrace());
            detector.trackAllocation(seg2, Thread.currentThread().getStackTrace());
            detector.trackAllocation(seg3, Thread.currentThread().getStackTrace());

            AllocationMetrics metrics = detector.getMetrics();
            assertThat(metrics.totalSegments()).isEqualTo(3);
            assertThat(metrics.totalBytes()).isEqualTo(600);

            detector.trackDeallocation(seg2);

            metrics = detector.getMetrics();
            assertThat(metrics.totalSegments()).isEqualTo(2);
            assertThat(metrics.totalBytes()).isEqualTo(400);
        }
    }

    @Test
    void nullSegmentIncrementsUntrackedCounter() {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(10));

        detector.trackAllocation(null, Thread.currentThread().getStackTrace());

        assertThat(detector.getUntrackedSegmentCount()).isEqualTo(1);
        assertThat(detector.getMetrics().untrackedSegmentCount()).isEqualTo(1);
        assertThat(detector.getMetrics().totalSegments()).isZero();
    }

    @Test
    void closedScopeSegmentIsUntrackable() {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(10));

        MemorySegment segment;
        try (Arena arena = Arena.ofConfined()) {
            segment = arena.allocate(256);
        }
        // Arena is now closed, so scope is not alive
        detector.trackAllocation(segment, Thread.currentThread().getStackTrace());

        assertThat(detector.getUntrackedSegmentCount()).isEqualTo(1);
        assertThat(detector.getMetrics().totalSegments()).isZero();
    }

    @Test
    void getLeakCandidatesWithShortThreshold() throws InterruptedException {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(1));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(2048);
            detector.trackAllocation(segment, Thread.currentThread().getStackTrace());

            // Initially no leak candidates
            assertThat(detector.getLeakCandidates(Duration.ofSeconds(1))).isEmpty();

            // Wait just over 1 second
            Thread.sleep(1100);

            List<LeakCandidate> candidates = detector.getLeakCandidates(Duration.ofSeconds(1));
            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).sizeBytes()).isEqualTo(2048);
            assertThat(candidates.get(0).allocationSite()).isNotEmpty();
            assertThat(candidates.get(0).elapsedTime()).isGreaterThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void metricsReflectThresholdExceedingCount() throws InterruptedException {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(1));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg1 = arena.allocate(100);
            detector.trackAllocation(seg1, Thread.currentThread().getStackTrace());

            Thread.sleep(1100);

            // Add another segment after the threshold
            MemorySegment seg2 = arena.allocate(200);
            detector.trackAllocation(seg2, Thread.currentThread().getStackTrace());

            AllocationMetrics metrics = detector.getMetrics();
            assertThat(metrics.totalSegments()).isEqualTo(2);
            assertThat(metrics.thresholdExceedingCount()).isEqualTo(1); // only seg1 exceeds
        }
    }

    @Test
    void segmentRemovedFromRegistryAfterArenaClose() throws InterruptedException {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(300));

        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(1024);
        detector.trackAllocation(segment, Thread.currentThread().getStackTrace());

        assertThat(detector.getMetrics().totalSegments()).isEqualTo(1);

        // Close the arena — the monitor thread should remove it within 1 second
        arena.close();

        // Wait up to 1 second for the monitor to detect and remove
        Thread.sleep(1000);

        assertThat(detector.getMetrics().totalSegments()).isZero();
    }

    @Test
    void trackDeallocationWithNullIsNoOp() {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(10));
        // Should not throw
        detector.trackDeallocation(null);
        assertThat(detector.getMetrics().totalSegments()).isZero();
    }

    @Test
    void leakCandidatesIncludeStackTrace() throws InterruptedException {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(1));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(512);
            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            detector.trackAllocation(segment, trace);

            // Wait briefly so elapsed time exceeds the 1-second threshold
            Thread.sleep(1100);

            List<LeakCandidate> candidates = detector.getLeakCandidates(Duration.ofSeconds(1));
            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).allocationSite()).isNotNull();
            assertThat(candidates.get(0).allocationSite().length).isGreaterThan(0);
        }
    }

    @Test
    void nullStackTraceIsHandledGracefully() throws InterruptedException {
        var detector = new PanamaMemoryDetector(Duration.ofSeconds(1));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(128);
            detector.trackAllocation(segment, null);

            // Wait briefly so elapsed time exceeds the 1-second threshold
            Thread.sleep(1100);

            List<LeakCandidate> candidates = detector.getLeakCandidates(Duration.ofSeconds(1));
            assertThat(candidates).hasSize(1);
            assertThat(candidates.get(0).allocationSite()).isEmpty();
        }
    }
}
