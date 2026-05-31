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

import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.index.ScoredResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Coordinator node for distributed Spector search.
 *
 * <p>Receives search queries from clients and fans them out to all shard nodes
 * in parallel via gRPC. Results are merged using a priority queue to maintain
 * global ordering.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Client → Coordinator → [Shard 1, Shard 2, ..., Shard N] → Merge → Client
 * </pre>
 *
 * <h3>Concurrency</h3>
 * <p>Uses {@link ConcurrentTasks#forkJoinAll} for parallel shard fan-out.
 * In structured concurrency mode (JEP 505), if any shard fails, all other
 * shard queries are automatically cancelled — preventing thread leaks.
 * Falls back to classic virtual-thread executor when structured concurrency
 * is disabled via {@code -Dspector.concurrency.structured=false}.</p>
 *
 * <h3>Search Flow</h3>
 * <ol>
 *   <li>Fan out the query to all shards in parallel</li>
 *   <li>Each shard returns its local top-K results</li>
 *   <li>Coordinator merges all results and returns global top-K</li>
 * </ol>
 *
 * <h3>Ingestion Flow</h3>
 * <ol>
 *   <li>Hash the document ID to determine target shard</li>
 *   <li>Route the ingest request to that specific shard</li>
 * </ol>
 */
public class ClusterCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClusterCoordinator.class);

    private final ClusterConfig config;
    private final List<RemoteShardClient> shardClients;

    /**
     * Creates a cluster coordinator.
     *
     * @param config cluster configuration with shard endpoints
     */
    public ClusterCoordinator(ClusterConfig config) {
        this.config = config;
        this.shardClients = new ArrayList<>();

        // Create gRPC clients for each shard
        for (var node : config.nodes()) {
            shardClients.add(new RemoteShardClient(node));
        }

        log.info("ClusterCoordinator initialized: {} shards, structuredConcurrency={}",
                config.shardCount(), ConcurrentTasks.isStructuredConcurrencyEnabled());
    }

    /**
     * Executes a distributed vector search across all shards.
     *
     * @param queryVector query vector
     * @param topK        number of results to return
     * @return merged top-K results from all shards
     */
    public ScoredResult[] vectorSearch(float[] queryVector, int topK) {
        long startTime = System.nanoTime();

        ScoredResult[] merged = fanOutAndMerge(
                shardClients.stream()
                        .map(client -> (Callable<ScoredResult[]>) () -> client.vectorSearch(queryVector, topK))
                        .toList(),
                topK);

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        log.debug("Distributed vector search: {} shards, {} results, {}ms",
                shardClients.size(), merged.length, elapsed);

        return merged;
    }

    /**
     * Executes a distributed keyword search across all shards.
     *
     * @param queryText query text
     * @param topK      number of results to return
     * @return merged top-K results from all shards
     */
    public ScoredResult[] keywordSearch(String queryText, int topK) {
        long startTime = System.nanoTime();

        ScoredResult[] merged = fanOutAndMerge(
                shardClients.stream()
                        .map(client -> (Callable<ScoredResult[]>) () -> client.keywordSearch(queryText, topK))
                        .toList(),
                topK);

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        log.debug("Distributed keyword search: {} shards, {} results, {}ms",
                shardClients.size(), merged.length, elapsed);

        return merged;
    }

    /**
     * Executes a distributed hybrid search across all shards.
     *
     * @param queryText   query text
     * @param queryVector query vector
     * @param topK        number of results to return
     * @return merged top-K results from all shards
     */
    public ScoredResult[] hybridSearch(String queryText, float[] queryVector, int topK) {
        long startTime = System.nanoTime();

        ScoredResult[] merged = fanOutAndMerge(
                shardClients.stream()
                        .map(client -> (Callable<ScoredResult[]>) () -> client.hybridSearch(queryText, queryVector, topK))
                        .toList(),
                topK);

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        log.debug("Distributed hybrid search: {} shards, {} results, {}ms",
                shardClients.size(), merged.length, elapsed);

        return merged;
    }

    /**
     * Ingests a document, routing it to the correct shard.
     *
     * @param docId   document ID
     * @param content document content
     * @param vector  pre-computed embedding (may be null)
     * @return true if ingestion succeeded
     */
    public boolean ingest(String docId, String content, float[] vector) {
        int shardIdx = config.shardFor(docId);
        RemoteShardClient client = shardClients.get(shardIdx);

        log.debug("Routing doc '{}' to shard {}", docId, config.nodes().get(shardIdx).shardId());
        return client.ingest(docId, content, vector);
    }

    /**
     * Checks health of all shard nodes.
     *
     * @return map of shard ID → health status
     */
    public Map<String, Boolean> healthCheck() {
        Map<String, Boolean> health = new LinkedHashMap<>();
        for (int i = 0; i < shardClients.size(); i++) {
            String shardId = config.nodes().get(i).shardId();
            try {
                health.put(shardId, shardClients.get(i).healthCheck());
            } catch (Exception e) {
                health.put(shardId, false);
            }
        }
        return health;
    }

    @Override
    public void close() {
        for (var client : shardClients) {
            client.close();
        }
        log.info("ClusterCoordinator closed");
    }

    // ─────────────── Core Fan-Out ───────────────

    /**
     * Fans out tasks in parallel using {@link ConcurrentTasks}, collects all results,
     * and merges into global top-K.
     */
    private ScoredResult[] fanOutAndMerge(List<Callable<ScoredResult[]>> tasks, int topK) {
        try {
            List<ScoredResult[]> shardResults = ConcurrentTasks.forkJoinAll(tasks);
            return mergeResults(shardResults, topK);
        } catch (ConcurrentExecutionException e) {
            log.warn("Shard search failed: {}", e.getCause().getMessage());
            return new ScoredResult[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Distributed search interrupted");
            return new ScoredResult[0];
        }
    }

    // ─────────────── Result merging ───────────────

    /**
     * Merges results from all shards into global top-K.
     * Sorts by score descending and takes top-K.
     */
    private ScoredResult[] mergeResults(List<ScoredResult[]> shardResults, int topK) {
        List<ScoredResult> allResults = new ArrayList<>();
        for (ScoredResult[] results : shardResults) {
            allResults.addAll(Arrays.asList(results));
        }

        allResults.sort(Comparator.naturalOrder()); // ScoredResult is Comparable (descending)
        int count = Math.min(topK, allResults.size());
        return allResults.subList(0, count).toArray(ScoredResult[]::new);
    }
}
