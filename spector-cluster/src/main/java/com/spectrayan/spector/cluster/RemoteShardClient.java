package com.spectrayan.spector.cluster;

import com.spectrayan.spector.cluster.proto.*;
import com.spectrayan.spector.index.ScoredResult;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for communicating with a remote shard node.
 *
 * <p>Wraps a gRPC channel and blocking stub to provide type-safe methods
 * for vector search, keyword search, hybrid search, and ingestion
 * on a remote {@link ShardNode}.</p>
 */
public class RemoteShardClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteShardClient.class);

    private final ClusterConfig.NodeEndpoint endpoint;
    private final ManagedChannel channel;
    private final SpectorSearchServiceGrpc.SpectorSearchServiceBlockingStub stub;

    /**
     * Creates a remote shard client.
     *
     * @param endpoint the shard node endpoint
     */
    public RemoteShardClient(ClusterConfig.NodeEndpoint endpoint) {
        this.endpoint = endpoint;
        this.channel = ManagedChannelBuilder
                .forTarget(endpoint.target())
                .usePlaintext()   // TODO: Add TLS for production
                .build();

        this.stub = SpectorSearchServiceGrpc.newBlockingStub(channel);

        log.info("Connected to shard '{}' at {}", endpoint.shardId(), endpoint.target());
    }

    /**
     * Executes a vector search on the remote shard.
     *
     * @param queryVector query vector
     * @param topK        number of results
     * @return shard-local results
     */
    public ScoredResult[] vectorSearch(float[] queryVector, int topK) {
        try {
            VectorSearchRequest request = VectorSearchRequest.newBuilder()
                    .addAllQueryVector(floatsToList(queryVector))
                    .setTopK(topK)
                    .build();
            SearchResponse response = stub.vectorSearch(request);
            return toScoredResults(response);
        } catch (Exception e) {
            log.warn("Vector search failed on shard '{}': {}", endpoint.shardId(), e.getMessage());
            return new ScoredResult[0];
        }
    }

    /**
     * Executes a keyword search on the remote shard.
     *
     * @param queryText query text
     * @param topK      number of results
     * @return shard-local results
     */
    public ScoredResult[] keywordSearch(String queryText, int topK) {
        try {
            KeywordSearchRequest request = KeywordSearchRequest.newBuilder()
                    .setQueryText(queryText)
                    .setTopK(topK)
                    .build();
            SearchResponse response = stub.keywordSearch(request);
            return toScoredResults(response);
        } catch (Exception e) {
            log.warn("Keyword search failed on shard '{}': {}", endpoint.shardId(), e.getMessage());
            return new ScoredResult[0];
        }
    }

    /**
     * Executes a hybrid search on the remote shard.
     *
     * @param queryText   query text
     * @param queryVector query vector
     * @param topK        number of results
     * @return shard-local results
     */
    public ScoredResult[] hybridSearch(String queryText, float[] queryVector, int topK) {
        try {
            HybridSearchRequest request = HybridSearchRequest.newBuilder()
                    .setQueryText(queryText)
                    .addAllQueryVector(floatsToList(queryVector))
                    .setTopK(topK)
                    .build();
            SearchResponse response = stub.hybridSearch(request);
            return toScoredResults(response);
        } catch (Exception e) {
            log.warn("Hybrid search failed on shard '{}': {}", endpoint.shardId(), e.getMessage());
            return new ScoredResult[0];
        }
    }

    /**
     * Ingests a document into the remote shard.
     *
     * @param docId   document ID
     * @param content document content
     * @param vector  pre-computed embedding (may be null)
     * @return true if successful
     */
    public boolean ingest(String docId, String content, float[] vector) {
        try {
            IngestRequest.Builder builder = IngestRequest.newBuilder()
                    .setDocId(docId)
                    .setContent(content);
            if (vector != null) {
                builder.addAllVector(floatsToList(vector));
            }
            IngestResponse response = stub.ingest(builder.build());
            return response.getSuccess();
        } catch (Exception e) {
            log.warn("Ingest failed on shard '{}': {}", endpoint.shardId(), e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the remote shard is healthy.
     *
     * @return true if the shard responds to health check
     */
    public boolean healthCheck() {
        try {
            HealthCheckResponse response = stub.healthCheck(
                    HealthCheckRequest.getDefaultInstance());
            return response.getHealthy();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
        log.info("Disconnected from shard '{}'", endpoint.shardId());
    }

    // ─────────────── Conversion helpers ───────────────

    private static List<Float> floatsToList(float[] arr) {
        var list = new ArrayList<Float>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private static ScoredResult[] toScoredResults(SearchResponse response) {
        return response.getResultsList().stream()
                .map(r -> new ScoredResult(r.getDocId(), r.getStoreIndex(), r.getScore()))
                .toArray(ScoredResult[]::new);
    }
}
