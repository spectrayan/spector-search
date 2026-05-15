package com.spectrayan.spector.cluster;

import com.spectrayan.spector.index.ScoredResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

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
    private final ExecutorService executor;

    /**
     * Creates a cluster coordinator.
     *
     * @param config cluster configuration with shard endpoints
     */
    public ClusterCoordinator(ClusterConfig config) {
        this.config = config;
        this.shardClients = new ArrayList<>();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create gRPC clients for each shard
        for (var node : config.nodes()) {
            shardClients.add(new RemoteShardClient(node));
        }

        log.info("ClusterCoordinator initialized: {} shards", config.shardCount());
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

        // Fan out to all shards in parallel
        List<Future<ScoredResult[]>> futures = new ArrayList<>();
        for (var client : shardClients) {
            futures.add(executor.submit(() -> client.vectorSearch(queryVector, topK)));
        }

        // Collect and merge results
        ScoredResult[] merged = collectAndMerge(futures, topK);

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

        List<Future<ScoredResult[]>> futures = new ArrayList<>();
        for (var client : shardClients) {
            futures.add(executor.submit(() -> client.keywordSearch(queryText, topK)));
        }

        ScoredResult[] merged = collectAndMerge(futures, topK);

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

        List<Future<ScoredResult[]>> futures = new ArrayList<>();
        for (var client : shardClients) {
            futures.add(executor.submit(() -> client.hybridSearch(queryText, queryVector, topK)));
        }

        ScoredResult[] merged = collectAndMerge(futures, topK);

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
        executor.close();
        log.info("ClusterCoordinator closed");
    }

    // ─────────────── Result merging ───────────────

    /**
     * Collects results from all shard futures and merges into global top-K.
     * Uses a min-heap to efficiently track the K best results across all shards.
     */
    private ScoredResult[] collectAndMerge(List<Future<ScoredResult[]>> futures, int topK) {
        // Collect all results
        List<ScoredResult> allResults = new ArrayList<>();
        for (var future : futures) {
            try {
                ScoredResult[] shardResults = future.get(10, TimeUnit.SECONDS);
                allResults.addAll(Arrays.asList(shardResults));
            } catch (TimeoutException e) {
                log.warn("Shard timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Merge interrupted");
            } catch (ExecutionException e) {
                log.warn("Shard search failed: {}", e.getCause().getMessage());
            }
        }

        // Sort by score descending and take top-K
        allResults.sort(Comparator.naturalOrder()); // ScoredResult is Comparable (descending)
        int count = Math.min(topK, allResults.size());
        return allResults.subList(0, count).toArray(ScoredResult[]::new);
    }
}
