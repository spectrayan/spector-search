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
package com.spectrayan.spector.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NeighborQueue}.
 */
class NeighborQueueTest {

    @Test
    void minHeapOrdering() {
        var q = new NeighborQueue(4, true);
        q.add(0, 3.0f);
        q.add(1, 1.0f);
        q.add(2, 2.0f);

        assertThat(q.topScore()).isEqualTo(1.0f);
        assertThat(q.poll()).isEqualTo(1);
        assertThat(q.topScore()).isEqualTo(2.0f);
    }

    @Test
    void maxHeapOrdering() {
        var q = new NeighborQueue(4, false);
        q.add(0, 1.0f);
        q.add(1, 3.0f);
        q.add(2, 2.0f);

        assertThat(q.topScore()).isEqualTo(3.0f);
        assertThat(q.poll()).isEqualTo(1);
    }

    @Test
    void boundedEviction() {
        // Max-heap bounded to 3: worst (highest score) on top, evict if new is smaller
        var q = new NeighborQueue(4, 3, false);
        q.add(0, 10f);
        q.add(1, 20f);
        q.add(2, 30f);

        // Full now. Adding 5f should evict 30f (top, worst in terms of distance)
        boolean added = q.add(3, 5f);
        assertThat(added).isTrue();
        assertThat(q.size()).isEqualTo(3);

        // Adding 50f should NOT be added (worse than worst remaining)
        added = q.add(4, 50f);
        assertThat(added).isFalse();
    }

    @Test
    void sizeAndEmpty() {
        var q = new NeighborQueue(4, true);
        assertThat(q.isEmpty()).isTrue();
        assertThat(q.size()).isEqualTo(0);

        q.add(0, 1.0f);
        assertThat(q.isEmpty()).isFalse();
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    void clear() {
        var q = new NeighborQueue(4, true);
        q.add(0, 1.0f);
        q.add(1, 2.0f);
        q.clear();
        assertThat(q.isEmpty()).isTrue();
    }

    @Test
    void growsBeyondInitialCapacity() {
        var q = new NeighborQueue(2, true);
        for (int i = 0; i < 100; i++) {
            q.add(i, i);
        }
        assertThat(q.size()).isEqualTo(100);
    }
}
