package com.spectrayan.spector.node.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.cluster.ClusterCoordinator;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.node.api.dto.SearchRequest;
import com.spectrayan.spector.node.api.dto.SearchResponseDto;
import com.spectrayan.spector.node.event.SpectorEventBus;
import com.spectrayan.spector.node.event.SpectorSearchCompletedEvent;
import com.spectrayan.spector.node.event.SpectorSearchFailedEvent;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorApiException;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

/**
 * Search service facade — encapsulates local vs cluster routing.
 *
 * <p>Applies the Strategy pattern internally: in standalone mode, queries
 * go directly to the local engine. In clustered mode, queries are fanned
 * out through the {@link ClusterCoordinator}.</p>
 *
 * <p>All search operations publish events to the {@link SpectorEventBus}
 * for subscribers (SSE clients, metrics, audit logging).</p>
 */
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final SpectorEngine engine;
    private final ClusterCoordinator coordinator; // nullable — null in standalone
    private final SpectorEventBus eventBus;
    private final String nodeId;

    public SearchService(SpectorEngine engine, ClusterCoordinator coordinator,
                         SpectorEventBus eventBus, String nodeId) {
        this.engine = engine;
        this.coordinator = coordinator;
        this.eventBus = eventBus;
        this.nodeId = nodeId;
    }

    /**
     * Executes a search query, routing to local engine or cluster coordinator.
     *
     * @param request the search request DTO
     * @return the search response DTO
     * @throws com.spectrayan.spector.commons.error.SpectorException if the search fails
     */
    public SearchResponseDto search(SearchRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        SearchQuery query = request.toQuery();
        long startNanos = System.nanoTime();

        try {
            SearchResponse response = executeSearch(query);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            eventBus.publish(new SpectorSearchCompletedEvent(
                    nodeId, Instant.now(),
                    response.totalHits(), latencyMs, response.mode().name()));

            return SearchResponseDto.from(response);

        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            eventBus.publish(new SpectorSearchFailedEvent(
                    nodeId, Instant.now(),
                    request.resolvedMode().name(), e.getMessage()));

            throw SpectorApiException.internal(ErrorCode.INTERNAL_ERROR, e, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Executes a search query and returns the raw engine response.
     * Used by streaming endpoints that need direct access to results.
     */
    public SearchResponse searchRaw(SearchQuery query) {
        return executeSearch(query);
    }

    private SearchResponse executeSearch(SearchQuery query) {
        if (coordinator != null) {
            // Clustered mode — fan-out by mode
            long start = System.nanoTime();
            ScoredResult[] results = switch (query.mode()) {
                case KEYWORD -> coordinator.keywordSearch(query.text(), query.topK());
                case VECTOR -> coordinator.vectorSearch(query.vector(), query.topK());
                case HYBRID -> coordinator.hybridSearch(query.text(), query.vector(), query.topK());
            };
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return new SearchResponse(results, results.length, elapsed, query.mode());
        }
        // Standalone — local engine
        return engine.search(query);
    }
}
