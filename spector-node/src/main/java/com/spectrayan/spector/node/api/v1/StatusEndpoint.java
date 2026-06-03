package com.spectrayan.spector.node.api.v1;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;

import com.spectrayan.spector.cluster.ClusterCoordinator;
import com.spectrayan.spector.core.simd.SimdCapability;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.node.NodeConfig;
import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.event.SpectorEventBus;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Map;

/**
 * Status and metrics API v1 endpoint.
 *
 * <ul>
 *   <li>{@code GET /status}  — engine status, SIMD info, cluster mode</li>
 *   <li>{@code GET /metrics} — request metrics and resource usage</li>
 * </ul>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class StatusEndpoint implements ApiModule {

    private final SpectorEngine engine;
    private final NodeConfig nodeConfig;
    private final SpectorEventBus eventBus;
    private final ClusterCoordinator coordinator; // nullable
    private final long startTime = System.currentTimeMillis();

    public StatusEndpoint(SpectorEngine engine, NodeConfig nodeConfig,
                          SpectorEventBus eventBus, ClusterCoordinator coordinator) {
        this.engine = engine;
        this.nodeConfig = nodeConfig;
        this.eventBus = eventBus;
        this.coordinator = coordinator;
    }

    @Override
    public String pathPrefix() { return "/engine"; }

    @Get("/status")
    public HttpResponse status() {
        var status = new java.util.LinkedHashMap<String, Object>();
        status.put("engine", "spector");
        status.put("version", "0.1.0-SNAPSHOT");
        status.put("nodeId", nodeConfig.nodeId());
        status.put("mode", nodeConfig.mode().name());
        status.put("documents", engine.documentCount());
        status.put("dimensions", engine.config().dimensions());
        status.put("similarity", engine.config().similarityFunction().name());
        status.put("indexType", engine.config().indexType().name());
        status.put("gpu", engine.isGpuActive() ? "active" : "inactive");
        status.put("reranker", engine.isRerankerActive() ? engine.reranker().modelName() : "disabled");
        status.put("embedding", engine.hasEmbeddingProvider() ? "configured" : "none");
        status.put("simd", SimdCapability.report());
        status.put("eventSubscribers", eventBus.subscriberCount());
        return HttpResponse.ofJson(status);
    }

    @Get("/metrics")
    public HttpResponse metrics() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        return HttpResponse.ofJson(Map.of(
                "uptimeMs", uptimeMs,
                "documents", engine.documentCount(),
                "gpu", engine.isGpuActive(),
                "reranker", engine.isRerankerActive(),
                "eventSubscribers", eventBus.subscriberCount()
        ));
    }
}
