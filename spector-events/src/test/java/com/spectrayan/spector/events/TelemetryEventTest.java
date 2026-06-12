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
package com.spectrayan.spector.events;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for all {@link TelemetryEvent} record types — construction, equality,
 * sealed hierarchy compliance, and accessor correctness.
 */
@DisplayName("TelemetryEvent Records")
class TelemetryEventTest {

    @Nested
    @DisplayName("SimdKernelTelemetry")
    class SimdKernelTests {

        @Test
        @DisplayName("construction and accessors")
        void constructionAndAccessors() {
            var t = new SimdKernelTelemetry("cosine", 16, 50000, 230_000L);
            assertThat(t.kernelName()).isEqualTo("cosine");
            assertThat(t.laneWidth()).isEqualTo(16);
            assertThat(t.vectorsProcessed()).isEqualTo(50000);
            assertThat(t.durationNanos()).isEqualTo(230_000L);
        }

        @Test
        @DisplayName("record equality")
        void recordEquality() {
            var a = new SimdKernelTelemetry("cosine", 16, 1000, 5000);
            var b = new SimdKernelTelemetry("cosine", 16, 1000, 5000);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("implements TelemetryEvent")
        void implementsSealedInterface() {
            TelemetryEvent event = new SimdKernelTelemetry("dot", 8, 1, 100);
            assertThat(event).isInstanceOf(TelemetryEvent.class);
        }

        @Test
        @DisplayName("toString contains fields")
        void toStringContainsFields() {
            var t = new SimdKernelTelemetry("euclidean", 4, 10, 999);
            assertThat(t.toString()).contains("euclidean", "4", "10", "999");
        }
    }

    @Nested
    @DisplayName("GraphPulseTelemetry")
    class GraphPulseTests {

        @Test
        @DisplayName("construction and accessors")
        void constructionAndAccessors() {
            var t = new GraphPulseTelemetry(100, 250, 5, 10_000L);
            assertThat(t.nodesVisited()).isEqualTo(100);
            assertThat(t.edgesTraversed()).isEqualTo(250);
            assertThat(t.maxDepth()).isEqualTo(5);
            assertThat(t.durationNanos()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("zero-value construction")
        void zeroValues() {
            var t = new GraphPulseTelemetry(0, 0, 0, 0);
            assertThat(t.nodesVisited()).isZero();
            assertThat(t.edgesTraversed()).isZero();
        }
    }

    @Nested
    @DisplayName("QueryTraceTelemetry")
    class QueryTraceTests {

        @Test
        @DisplayName("construction and accessors")
        void constructionAndAccessors() {
            var t = new QueryTraceTelemetry(
                    "test query", "BALANCED", 1000, 900, 800, 700, 600, 500, 10, 5000);
            assertThat(t.queryText()).isEqualTo("test query");
            assertThat(t.cognitiveProfile()).isEqualTo("BALANCED");
            assertThat(t.totalRecords()).isEqualTo(1000);
            assertThat(t.afterTombstone()).isEqualTo(900);
            assertThat(t.afterTagGate()).isEqualTo(800);
            assertThat(t.afterValence()).isEqualTo(700);
            assertThat(t.afterDecay()).isEqualTo(600);
            assertThat(t.afterVector()).isEqualTo(500);
            assertThat(t.finalTopK()).isEqualTo(10);
            assertThat(t.latencyMicros()).isEqualTo(5000);
        }

        @Test
        @DisplayName("funnel shows progressive filtering")
        void funnelProgressiveFiltering() {
            var t = new QueryTraceTelemetry(
                    "q", "P", 100, 90, 80, 70, 60, 50, 10, 1000);
            assertThat(t.totalRecords()).isGreaterThanOrEqualTo(t.afterTombstone());
            assertThat(t.afterTombstone()).isGreaterThanOrEqualTo(t.afterTagGate());
            assertThat(t.afterTagGate()).isGreaterThanOrEqualTo(t.afterValence());
            assertThat(t.afterValence()).isGreaterThanOrEqualTo(t.afterDecay());
            assertThat(t.afterDecay()).isGreaterThanOrEqualTo(t.afterVector());
            assertThat(t.afterVector()).isGreaterThanOrEqualTo(t.finalTopK());
        }
    }

    @Nested
    @DisplayName("MemoryDiagnosticTelemetry")
    class MemoryDiagnosticTests {

        @Test
        @DisplayName("construction with all fields")
        void constructionAllFields() {
            var t = new MemoryDiagnosticTelemetry(
                    1024 * 1024L, 512 * 1024L,
                    256 * 1024 * 1024L, 512 * 1024 * 1024L,
                    2048 * 1024L, 1024 * 1024L,
                    10, 2,
                    100, 50, 200, 30,
                    500, 150, 75, 120, 80, 40);
            assertThat(t.offHeapAllocated()).isEqualTo(1024 * 1024L);
            assertThat(t.workingCount()).isEqualTo(100);
            assertThat(t.semanticCount()).isEqualTo(200);
            assertThat(t.hebbianEdges()).isEqualTo(500);
        }

        @Test
        @DisplayName("zero counts are valid")
        void zeroCounts() {
            var t = new MemoryDiagnosticTelemetry(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            assertThat(t.workingCount()).isZero();
            assertThat(t.hebbianEdges()).isZero();
        }
    }

    @Nested
    @DisplayName("MemorySnapshotTelemetry")
    class MemorySnapshotTests {

        @Test
        @DisplayName("pre-reflect and post-reflect phases")
        void preAndPostPhases() {
            var pre = new MemorySnapshotTelemetry("pre-reflect", "cycle-001", 10, 5, 3, 8, 1024, 2, 15, 7);
            var post = new MemorySnapshotTelemetry("post-reflect", "cycle-001", 8, 5, 3, 8, 1024, 2, 12, 5);

            assertThat(pre.phase()).isEqualTo("pre-reflect");
            assertThat(post.phase()).isEqualTo("post-reflect");
            assertThat(pre.reflectCycleId()).isEqualTo(post.reflectCycleId());
        }
    }

    @Nested
    @DisplayName("ReflectCycleTelemetry")
    class ReflectCycleTests {

        @Test
        @DisplayName("construction and accessors")
        void constructionAndAccessors() {
            var t = new ReflectCycleTelemetry(50, 10, 0.95, 250);
            assertThat(t.hebbianEdgesDecayed()).isEqualTo(50);
            assertThat(t.hebbianEdgesRemoved()).isEqualTo(10);
            assertThat(t.decayFactor()).isCloseTo(0.95, within(0.001));
            assertThat(t.durationMs()).isEqualTo(250);
        }
    }

    @Nested
    @DisplayName("ClusterTopologyTelemetry")
    class ClusterTopologyTests {

        @Test
        @DisplayName("construction with nodes and replication links")
        void constructionWithNodes() {
            var node1 = new ClusterTopologyTelemetry.ClusterNodeSnapshot(
                    "node-1", "active", 4, 1024 * 1024, 150.5);
            var node2 = new ClusterTopologyTelemetry.ClusterNodeSnapshot(
                    "node-2", "draining", 2, 512 * 1024, 50.0);

            List<String[]> links = new java.util.ArrayList<>();
            links.add(new String[]{"node-1", "node-2"});
            var t = new ClusterTopologyTelemetry(List.of(node1, node2), links);

            assertThat(t.nodes()).hasSize(2);
            assertThat(t.nodes().get(0).nodeId()).isEqualTo("node-1");
            assertThat(t.nodes().get(0).status()).isEqualTo("active");
            assertThat(t.replicationLinks()).hasSize(1);
        }

        @Test
        @DisplayName("empty cluster")
        void emptyCluster() {
            var t = new ClusterTopologyTelemetry(List.of(), List.of());
            assertThat(t.nodes()).isEmpty();
            assertThat(t.replicationLinks()).isEmpty();
        }

        @Test
        @DisplayName("node snapshot accessors")
        void nodeSnapshotAccessors() {
            var snap = new ClusterTopologyTelemetry.ClusterNodeSnapshot(
                    "n1", "down", 0, 0, 0.0);
            assertThat(snap.nodeId()).isEqualTo("n1");
            assertThat(snap.status()).isEqualTo("down");
            assertThat(snap.shardCount()).isZero();
            assertThat(snap.memoryUsedBytes()).isZero();
            assertThat(snap.queryRate()).isZero();
        }
    }

    @Nested
    @DisplayName("EmbeddingProjectionTelemetry")
    class EmbeddingProjectionTests {

        @Test
        @DisplayName("construction with points and query projection")
        void constructionWithPointsAndQuery() {
            var point = new EmbeddingProjectionTelemetry.ProjectedPoint(
                    "mem-1", 0.5f, -0.3f, 0.8f, "SEMANTIC", 0.9f, "Architecture doc");
            var queryProj = new float[]{0.1f, 0.2f, 0.3f};

            var t = new EmbeddingProjectionTelemetry(List.of(point), queryProj);

            assertThat(t.points()).hasSize(1);
            assertThat(t.points().get(0).id()).isEqualTo("mem-1");
            assertThat(t.points().get(0).tier()).isEqualTo("SEMANTIC");
            assertThat(t.queryProjection()).containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("null query projection is valid")
        void nullQueryProjection() {
            var t = new EmbeddingProjectionTelemetry(List.of(), null);
            assertThat(t.queryProjection()).isNull();
            assertThat(t.points()).isEmpty();
        }

        @Test
        @DisplayName("projected point accessors")
        void projectedPointAccessors() {
            var p = new EmbeddingProjectionTelemetry.ProjectedPoint(
                    "id-1", 1.0f, 2.0f, 3.0f, "WORKING", 0.5f, "Test label");
            assertThat(p.x()).isEqualTo(1.0f);
            assertThat(p.y()).isEqualTo(2.0f);
            assertThat(p.z()).isEqualTo(3.0f);
            assertThat(p.importance()).isEqualTo(0.5f);
            assertThat(p.label()).isEqualTo("Test label");
        }
    }

    @Nested
    @DisplayName("GpuKernelTelemetry")
    class GpuKernelTests {

        @Test
        @DisplayName("construction and accessors")
        void constructionAndAccessors() {
            var t = new GpuKernelTelemetry(0, "batch_cosine", 50_000L, 128, 1, 1, 256, 1, 1, 4096);
            assertThat(t.streamIndex()).isZero();
            assertThat(t.kernelName()).isEqualTo("batch_cosine");
            assertThat(t.durationNanos()).isEqualTo(50_000L);
            assertThat(t.gridDimX()).isEqualTo(128);
            assertThat(t.blockDimX()).isEqualTo(256);
            assertThat(t.memoryTransferBytes()).isEqualTo(4096);
        }

        @Test
        @DisplayName("implements TelemetryEvent")
        void implementsInterface() {
            TelemetryEvent event = new GpuKernelTelemetry(0, "test", 100, 1, 1, 1, 1, 1, 1, 0);
            assertThat(event).isInstanceOf(TelemetryEvent.class);
            assertThat(event).isInstanceOf(GpuKernelTelemetry.class);
        }
    }

    // ── Sealed hierarchy completeness ──

    @Test
    @DisplayName("sealed hierarchy has exactly 9 permitted types")
    void sealedHierarchyCompleteness() {
        Class<?>[] permitted = TelemetryEvent.class.getPermittedSubclasses();
        assertThat(permitted).hasSize(9);
        assertThat(permitted).extracting(Class::getSimpleName).containsExactlyInAnyOrder(
                "SimdKernelTelemetry",
                "GraphPulseTelemetry",
                "GpuKernelTelemetry",
                "QueryTraceTelemetry",
                "MemorySnapshotTelemetry",
                "MemoryDiagnosticTelemetry",
                "ReflectCycleTelemetry",
                "ClusterTopologyTelemetry",
                "EmbeddingProjectionTelemetry"
        );
    }
}
