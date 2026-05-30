package com.spectrayan.spector.cluster;

import com.spectrayan.spector.cluster.proto.*;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import io.grpc.stub.StreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * gRPC service implementation for a search shard node.
 *
 * <p>Delegates all RPC calls to the local {@link SpectorEngine} instance
 * and converts between protobuf messages and internal domain objects.</p>
 */
public class SpectorSearchServiceImpl
        extends SpectorSearchServiceGrpc.SpectorSearchServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(SpectorSearchServiceImpl.class);

    private final String shardId;
    private final SpectorEngine engine;

    public SpectorSearchServiceImpl(String shardId, SpectorEngine engine) {
        this.shardId = shardId;
        this.engine = engine;
    }

    @Override
    public void vectorSearch(VectorSearchRequest request,
                             StreamObserver<com.spectrayan.spector.cluster.proto.SearchResponse> responseObserver) {
        try {
            float[] queryVector = toFloatArray(request.getQueryVectorList());
            SearchResponse result = engine.vectorSearch(queryVector, request.getTopK());

            responseObserver.onNext(toProtoResponse(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Vector search failed on shard '{}'", shardId, e);
            responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
        }
    }

    @Override
    public void keywordSearch(KeywordSearchRequest request,
                              StreamObserver<com.spectrayan.spector.cluster.proto.SearchResponse> responseObserver) {
        try {
            SearchResponse result = engine.keywordSearch(request.getQueryText(), request.getTopK());

            responseObserver.onNext(toProtoResponse(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Keyword search failed on shard '{}'", shardId, e);
            responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
        }
    }

    @Override
    public void hybridSearch(HybridSearchRequest request,
                             StreamObserver<com.spectrayan.spector.cluster.proto.SearchResponse> responseObserver) {
        try {
            float[] queryVector = toFloatArray(request.getQueryVectorList());
            SearchResponse result = engine.hybridSearch(
                    request.getQueryText(), queryVector, request.getTopK());

            responseObserver.onNext(toProtoResponse(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Hybrid search failed on shard '{}'", shardId, e);
            responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
        }
    }

    @Override
    public void ingest(IngestRequest request,
                       StreamObserver<IngestResponse> responseObserver) {
        try {
            float[] vector = request.getVectorCount() > 0
                    ? toFloatArray(request.getVectorList())
                    : null;

            if (vector != null) {
                engine.ingest(request.getDocId(), request.getContent(), vector);
            } else {
                engine.ingest(request.getDocId(), request.getContent());
            }

            responseObserver.onNext(IngestResponse.newBuilder()
                    .setSuccess(true)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Ingest failed on shard '{}'", shardId, e);
            responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
        }
    }

    @Override
    public void healthCheck(HealthCheckRequest request,
                            StreamObserver<HealthCheckResponse> responseObserver) {
        responseObserver.onNext(HealthCheckResponse.newBuilder()
                .setHealthy(true)
                .setShardId(shardId)
                .setDocCount(engine.documentCount())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getStats(StatsRequest request,
                         StreamObserver<StatsResponse> responseObserver) {
        responseObserver.onNext(StatsResponse.newBuilder()
                .setShardId(shardId)
                .setDocCount(engine.documentCount())
                .setVectorCount(engine.documentCount())
                .setMemoryUsedBytes(Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory())
                .setIndexType(engine.config().indexType().name())
                .build());
        responseObserver.onCompleted();
    }

    // ─────────────── Conversion helpers ───────────────

    private com.spectrayan.spector.cluster.proto.SearchResponse toProtoResponse(SearchResponse result) {
        var builder = com.spectrayan.spector.cluster.proto.SearchResponse.newBuilder()
                .setLatencyMs(result.queryTimeMs())
                .setShardId(shardId);

        for (ScoredResult sr : result.results()) {
            builder.addResults(com.spectrayan.spector.cluster.proto.ScoredResult.newBuilder()
                    .setDocId(sr.id())
                    .setStoreIndex(sr.index())
                    .setScore(sr.score())
                    .build());
        }

        return builder.build();
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
