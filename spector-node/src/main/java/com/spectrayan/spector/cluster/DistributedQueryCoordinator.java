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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.commons.concurrent.ConcurrentTasks.LabeledTask;
import com.spectrayan.spector.commons.concurrent.ConcurrentTasks.PartialResult;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Distributed query coordinator that fans out search queries to all shards
 * in parallel via gRPC and merges results with deduplication.
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>Fans out to all shards concurrently — no sequential shard-to-shard dependency</li>
 *   <li>Merges results by descending score; deduplicates by document ID (highest score wins)</li>
 *   <li>Returns partial results when some shards time out, with metadata indicating which shards timed out</li>
 *   <li>Returns empty result with error when all shards are unreachable</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * <p>Uses {@link ConcurrentTasks#forkJoinPartial} for deadline-based fan-out.
 * In structured concurrency mode (JEP 505), uses {@code awaitAll()} joiner with
 * {@code Configuration.withTimeout()} for clean timeout handling. Falls back to
 * classic virtual-thread executor with per-future timeouts when disabled.</p>
 *
 * <h3>Timeout</h3>
 * <p>Configurable between 1 and 60 seconds (default: 10 seconds).</p>
 */
public class DistributedQueryCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DistributedQueryCoordinator.class);

    /** Minimum allowed timeout in seconds. */
    private static final int MIN_TIMEOUT_SECONDS = 1;

    /** Maximum allowed timeout in seconds. */
    private static final int MAX_TIMEOUT_SECONDS = 60;

    /** Default timeout in seconds. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final List<ShardEndpoint> shardEndpoints;
    private final Duration timeout;

    /**
     * Creates a coordinator with default timeout (10s).
     *
     * @param shardEndpoints the shard endpoints to fan out queries to
     */
    public DistributedQueryCoordinator(List<ShardEndpoint> shardEndpoints) {
        this(shardEndpoints, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    /**
     * Creates a coordinator with a custom timeout.
     *
     * @param shardEndpoints the shard endpoints to fan out queries to
     * @param timeout        per-shard timeout (must be between 1s and 60s)
     * @throws SpectorValidationException if timeout is outside the allowed range
     */
    public DistributedQueryCoordinator(List<ShardEndpoint> shardEndpoints, Duration timeout) {
        if (shardEndpoints == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "shardEndpoints"); }
        if (timeout == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "timeout"); }

        long timeoutSeconds = timeout.toSeconds();
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "Timeout", MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, timeoutSeconds);
        }

        this.shardEndpoints = List.copyOf(shardEndpoints);
        this.timeout = timeout;
    }

    /**
     * Fans out a vector search to all shards, merges and deduplicates results.
     *
     * @param queryVector the query vector
     * @param topK        number of top results to return (1–10,000)
     * @return merged query result with metadata about timed-out shards
     */
    public QueryResult fanOutVectorSearch(float[] queryVector, int topK) {
        if (queryVector == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "queryVector"); }
        validateTopK(topK);

        return fanOut(shardEndpoints, client -> client.vectorSearch(queryVector, topK), topK);
    }

    /**
     * Fans out a keyword search to all shards, merges and deduplicates results.
     *
     * @param queryText the query text
     * @param topK      number of top results to return (1–10,000)
     * @return merged query result with metadata about timed-out shards
     */
    public QueryResult fanOutKeywordSearch(String queryText, int topK) {
        if (queryText == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "queryText"); }
        validateTopK(topK);

        return fanOut(shardEndpoints, client -> client.keywordSearch(queryText, topK), topK);
    }

    /**
     * Fans out a hybrid search to all shards, merges and deduplicates results.
     *
     * @param queryText   the query text
     * @param queryVector the query vector
     * @param topK        number of top results to return (1–10,000)
     * @return merged query result with metadata about timed-out shards
     */
    public QueryResult fanOutHybridSearch(String queryText, float[] queryVector, int topK) {
        if (queryText == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "queryText"); }
        if (queryVector == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "queryVector"); }
        validateTopK(topK);

        return fanOut(shardEndpoints, client -> client.hybridSearch(queryText, queryVector, topK), topK);
    }

    /**
     * Returns the configured timeout.
     */
    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public void close() {
        // No executor to close — ConcurrentTasks manages scope per-call
        log.info("DistributedQueryCoordinator closed");
    }

    // ─────────────── Core Fan-Out Logic ───────────────

    /**
     * Generic fan-out that issues requests in parallel via {@link ConcurrentTasks#forkJoinPartial},
     * collects results with timeout, merges by descending score with deduplication,
     * and returns appropriate result type.
     */
    private QueryResult fanOut(List<ShardEndpoint> shards,
                               ShardSearchFunction searchFn,
                               int topK) {
        if (shards.isEmpty()) {
            return QueryResult.allShardsUnreachable(List.of());
        }

        // Build labeled tasks for each shard
        List<LabeledTask<ScoredResult[]>> tasks = shards.stream()
                .map(shard -> new LabeledTask<>(shard.shardId(),
                        (Callable<ScoredResult[]>) () -> {
                            try (RemoteShardClient client = new RemoteShardClient(shard.toNodeEndpoint())) {
                                return searchFn.search(client);
                            }
                        }))
                .toList();

        try {
            PartialResult<ScoredResult[]> partial = ConcurrentTasks.forkJoinPartial(tasks, timeout);

            // Log timeouts and failures
            for (String shardId : partial.timedOut()) {
                log.warn("Shard '{}' timed out after {}s", shardId, timeout.toSeconds());
            }
            for (PartialResult.Failure failure : partial.failures()) {
                log.warn("Shard '{}' failed: {}", failure.label(), failure.cause().getMessage());
            }

            // All shards unreachable
            if (partial.allFailed()) {
                return QueryResult.allShardsUnreachable(partial.unreachableLabels());
            }

            // Collect successful results
            List<ScoredResult> allResults = new ArrayList<>();
            for (PartialResult.Entry<ScoredResult[]> entry : partial.successes()) {
                if (entry.result() != null) {
                    allResults.addAll(Arrays.asList(entry.result()));
                }
            }

            // Merge and deduplicate
            List<ScoredResult> merged = mergeAndDeduplicate(allResults, topK);

            // Return partial or complete
            if (!partial.timedOut().isEmpty()) {
                return QueryResult.partial(merged, partial.timedOut());
            }
            return QueryResult.complete(merged);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Distributed query interrupted");
            return QueryResult.allShardsUnreachable(
                    shards.stream().map(ShardEndpoint::shardId).toList());
        }
    }

    // ─────────────── Merge and Deduplication ───────────────

    /**
     * Merges results from all shards:
     * <ol>
     *   <li>Deduplicates by document ID, keeping the highest score</li>
     *   <li>Sorts by descending score</li>
     *   <li>Returns top-K</li>
     * </ol>
     */
    static List<ScoredResult> mergeAndDeduplicate(List<ScoredResult> results, int topK) {
        if (results.isEmpty()) {
            return List.of();
        }

        // Deduplicate: keep highest score per document ID
        Map<String, ScoredResult> bestByDocId = new HashMap<>();
        for (ScoredResult result : results) {
            bestByDocId.merge(result.id(), result, (existing, incoming) ->
                    incoming.score() > existing.score() ? incoming : existing);
        }

        // Sort by score descending and take top-K
        List<ScoredResult> merged = new ArrayList<>(bestByDocId.values());
        merged.sort(Comparator.naturalOrder()); // ScoredResult.compareTo is descending
        if (merged.size() > topK) {
            return List.copyOf(merged.subList(0, topK));
        }
        return List.copyOf(merged);
    }

    // ─────────────── Validation ───────────────

    private static void validateTopK(int topK) {
        if (topK < 1 || topK > 10_000) {
            throw new SpectorValidationException(ErrorCode.TOP_K_INVALID, 1, topK);
        }
    }

    // ─────────────── Internal Types ───────────────

    /**
     * Functional interface for shard search operations.
     */
    @FunctionalInterface
    interface ShardSearchFunction {
        ScoredResult[] search(RemoteShardClient client);
    }

    /**
     * Represents a shard endpoint for query fan-out.
     *
     * @param shardId unique shard identifier
     * @param host    hostname or IP
     * @param port    gRPC port
     */
    public record ShardEndpoint(String shardId, String host, int port) {

        public ShardEndpoint {
            if (shardId == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "shardId"); }
            if (host == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "host"); }
            if (port <= 0 || port > 65535) {
                throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "port", 1, 65535, port);
            }
        }

        /**
         * Converts to ClusterConfig.NodeEndpoint for use with RemoteShardClient.
         */
        ClusterConfig.NodeEndpoint toNodeEndpoint() {
            return new ClusterConfig.NodeEndpoint(shardId, host, port);
        }
    }
}
