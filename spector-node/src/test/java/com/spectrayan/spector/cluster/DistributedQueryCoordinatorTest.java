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
package com.spectrayan.spector.cluster;

import com.spectrayan.spector.commons.error.SpectorValidationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.spectrayan.spector.index.ScoredResult;

/**
 * Unit tests for {@link DistributedQueryCoordinator}.
 *
 * <p>Tests focus on merge logic, deduplication, timeout validation,
 * and error handling. Network-level fan-out is tested via integration tests.</p>
 */
class DistributedQueryCoordinatorTest {

    // ─────────── Merge and Deduplication Tests ───────────

    @Test
    void mergeAndDeduplicate_emptyInput_returnsEmpty() {
        List<ScoredResult> result = DistributedQueryCoordinator.mergeAndDeduplicate(List.of(), 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void mergeAndDeduplicate_singleResult_returnsSame() {
        List<ScoredResult> input = List.of(new ScoredResult("doc1", 0, 0.9f));
        List<ScoredResult> result = DistributedQueryCoordinator.mergeAndDeduplicate(input, 10);

        assertEquals(1, result.size());
        assertEquals("doc1", result.get(0).id());
        assertEquals(0.9f, result.get(0).score());
    }

    @Test
    void mergeAndDeduplicate_duplicateDocIds_keepsHighestScore() {
        List<ScoredResult> input = List.of(
                new ScoredResult("doc1", 0, 0.7f),
                new ScoredResult("doc1", 1, 0.9f),
                new ScoredResult("doc1", 2, 0.5f)
        );
        List<ScoredResult> result = DistributedQueryCoordinator.mergeAndDeduplicate(input, 10);

        assertEquals(1, result.size());
        assertEquals("doc1", result.get(0).id());
        assertEquals(0.9f, result.get(0).score());
    }

    @Test
    void mergeAndDeduplicate_multipleDocsDescendingOrder() {
        List<ScoredResult> input = List.of(
                new ScoredResult("doc1", 0, 0.5f),
                new ScoredResult("doc2", 1, 0.9f),
                new ScoredResult("doc3", 2, 0.7f)
        );
        List<ScoredResult> result = DistributedQueryCoordinator.mergeAndDeduplicate(input, 10);

        assertEquals(3, result.size());
        assertEquals("doc2", result.get(0).id()); // 0.9
        assertEquals("doc3", result.get(1).id()); // 0.7
        assertEquals("doc1", result.get(2).id()); // 0.5
    }

    @Test
    void mergeAndDeduplicate_respectsTopK() {
        List<ScoredResult> input = List.of(
                new ScoredResult("doc1", 0, 0.9f),
                new ScoredResult("doc2", 1, 0.8f),
                new ScoredResult("doc3", 2, 0.7f),
                new ScoredResult("doc4", 3, 0.6f),
                new ScoredResult("doc5", 4, 0.5f)
        );
        List<ScoredResult> result = DistributedQueryCoordinator.mergeAndDeduplicate(input, 3);

        assertEquals(3, result.size());
        assertEquals("doc1", result.get(0).id());
        assertEquals("doc2", result.get(1).id());
        assertEquals("doc3", result.get(2).id());
    }

    @Test
    void mergeAndDeduplicate_duplicatesAcrossShards_mergesCorrectly() {
        // Simulates results from different shards with overlapping doc IDs
        List<ScoredResult> input = new ArrayList<>();
        // Shard 1 results
        input.add(new ScoredResult("doc1", 0, 0.9f));
        input.add(new ScoredResult("doc2", 1, 0.8f));
        // Shard 2 results
        input.add(new ScoredResult("doc2", 5, 0.85f)); // duplicate, higher score
        input.add(new ScoredResult("doc3", 6, 0.7f));

        List<ScoredResult> result = DistributedQueryCoordinator.mergeAndDeduplicate(input, 10);

        assertEquals(3, result.size());
        assertEquals("doc1", result.get(0).id()); // 0.9
        assertEquals("doc2", result.get(1).id()); // 0.85 (highest from shard 2)
        assertEquals(0.85f, result.get(1).score());
        assertEquals("doc3", result.get(2).id()); // 0.7
    }

    // ─────────── Timeout Validation Tests ───────────

    @Test
    void constructor_defaultTimeout_is10Seconds() {
        var coordinator = new DistributedQueryCoordinator(List.of());
        assertEquals(Duration.ofSeconds(10), coordinator.getTimeout());
        coordinator.close();
    }

    @Test
    void constructor_validTimeout_accepted() {
        var coordinator = new DistributedQueryCoordinator(List.of(), Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), coordinator.getTimeout());
        coordinator.close();

        coordinator = new DistributedQueryCoordinator(List.of(), Duration.ofSeconds(60));
        assertEquals(Duration.ofSeconds(60), coordinator.getTimeout());
        coordinator.close();
    }

    @Test
    void constructor_timeoutTooLow_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator(List.of(), Duration.ofMillis(500)));
    }

    @Test
    void constructor_timeoutTooHigh_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator(List.of(), Duration.ofSeconds(61)));
    }

    @Test
    void constructor_nullShardEndpoints_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator(null));
    }

    @Test
    void constructor_nullTimeout_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator(List.of(), null));
    }

    // ─────────── TopK Validation Tests ───────────

    @Test
    void fanOutVectorSearch_topKZero_throws() {
        var coordinator = new DistributedQueryCoordinator(List.of());
        assertThrows(SpectorValidationException.class, () ->
                coordinator.fanOutVectorSearch(new float[]{1.0f}, 0));
        coordinator.close();
    }

    @Test
    void fanOutVectorSearch_topKTooLarge_throws() {
        var coordinator = new DistributedQueryCoordinator(List.of());
        assertThrows(SpectorValidationException.class, () ->
                coordinator.fanOutVectorSearch(new float[]{1.0f}, 10_001));
        coordinator.close();
    }

    @Test
    void fanOutVectorSearch_nullQuery_throws() {
        var coordinator = new DistributedQueryCoordinator(List.of());
        assertThrows(SpectorValidationException.class, () ->
                coordinator.fanOutVectorSearch(null, 10));
        coordinator.close();
    }

    // ─────────── Empty Shard List Tests ───────────

    @Test
    void fanOutVectorSearch_noShards_returnsAllUnreachable() {
        var coordinator = new DistributedQueryCoordinator(List.of());
        QueryResult result = coordinator.fanOutVectorSearch(new float[]{1.0f, 0.5f}, 10);

        assertTrue(result.results().isEmpty());
        assertNotNull(result.error());
        coordinator.close();
    }

    // ─────────── ShardEndpoint Validation Tests ───────────

    @Test
    void shardEndpoint_validConstruction() {
        var endpoint = new DistributedQueryCoordinator.ShardEndpoint("shard-0", "localhost", 9090);
        assertEquals("shard-0", endpoint.shardId());
        assertEquals("localhost", endpoint.host());
        assertEquals(9090, endpoint.port());
    }

    @Test
    void shardEndpoint_invalidPort_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator.ShardEndpoint("shard-0", "localhost", 0));
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator.ShardEndpoint("shard-0", "localhost", -1));
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator.ShardEndpoint("shard-0", "localhost", 70000));
    }

    @Test
    void shardEndpoint_nullShardId_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator.ShardEndpoint(null, "localhost", 9090));
    }

    @Test
    void shardEndpoint_nullHost_throws() {
        assertThrows(SpectorValidationException.class, () ->
                new DistributedQueryCoordinator.ShardEndpoint("shard-0", null, 9090));
    }

    // ─────────── QueryResult Factory Tests ───────────

    @Test
    void queryResult_complete_hasNoError() {
        var results = List.of(new ScoredResult("doc1", 0, 0.9f));
        QueryResult qr = QueryResult.complete(results);

        assertEquals(results, qr.results());
        assertTrue(qr.timedOutShards().isEmpty());
        assertFalse(qr.partial());
        assertNull(qr.error());
    }

    @Test
    void queryResult_partial_indicatesTimedOutShards() {
        var results = List.of(new ScoredResult("doc1", 0, 0.9f));
        QueryResult qr = QueryResult.partial(results, List.of("shard-2"));

        assertEquals(results, qr.results());
        assertEquals(List.of("shard-2"), qr.timedOutShards());
        assertTrue(qr.partial());
        assertNull(qr.error());
    }

    @Test
    void queryResult_allShardsUnreachable_hasError() {
        QueryResult qr = QueryResult.allShardsUnreachable(List.of("shard-0", "shard-1"));

        assertTrue(qr.results().isEmpty());
        assertFalse(qr.partial());
        assertNotNull(qr.error());
        assertTrue(qr.error().contains("shard-0"));
        assertTrue(qr.error().contains("shard-1"));
    }
}
