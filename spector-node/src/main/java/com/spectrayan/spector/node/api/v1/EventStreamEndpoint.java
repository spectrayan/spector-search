package com.spectrayan.spector.node.api.v1;

import java.time.Duration;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesEventStream;

import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.event.SpectorEvent;
import com.spectrayan.spector.node.event.SpectorEventBus;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * SSE event stream endpoint — clients subscribe to live Spector events.
 *
 * <h3>Usage</h3>
 * <pre>
 *   GET /api/v1/events                       — all events
 *   GET /api/v1/events?filter=search,document — only search + document events
 *   GET /api/v1/events?filter=cluster         — only cluster events
 * </pre>
 *
 * <h3>Event Format</h3>
 * <pre>
 *   event: search.completed
 *   data: {"nodeId":"node-1","resultCount":5,"latencyMs":12,"searchMode":"HYBRID"}
 *
 *   event: document.ingested
 *   data: {"nodeId":"node-1","documentId":"doc-1","autoEmbedded":false}
 * </pre>
 *
 * <h3>Filter Categories</h3>
 * <ul>
 *   <li>{@code node} — lifecycle events (started, stopping, health)</li>
 *   <li>{@code search} — search completed/failed events</li>
 *   <li>{@code document} — ingest/delete events</li>
 *   <li>{@code cluster} — node join/leave, shard rebalance, replica sync</li>
 *   <li>{@code mcp} — MCP client connect/disconnect, tool execution</li>
 *   <li>{@code engine} — index rebuild, embedding provider changes</li>
 * </ul>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class EventStreamEndpoint implements ApiModule {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final SpectorEventBus eventBus;

    public EventStreamEndpoint(SpectorEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public String pathPrefix() { return ""; }

    @Get("/events")
    @ProducesEventStream
    public Publisher<ServerSentEvent> eventStream(
            @Param("filter") String filter) {

        Set<String> categories = parseFilter(filter);

        Sinks.Many<ServerSentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        SpectorEventBus.Subscription subscription = eventBus.subscribe(event -> {
            if (!categories.isEmpty() && !matchesFilter(event, categories)) {
                return;
            }
            try {
                String data = MAPPER.writeValueAsString(event);
                ServerSentEvent sse = ServerSentEvent.builder()
                        .event(event.eventType())
                        .data(data)
                        .build();
                sink.tryEmitNext(sse);
            } catch (Exception e) {
                // Skip events that fail serialization
            }
        });

        // Send heartbeat every 30s to keep connection alive
        Flux<ServerSentEvent> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(tick -> ServerSentEvent.builder()
                        .event("heartbeat")
                        .data("{}")
                        .build());

        return Flux.merge(sink.asFlux(), heartbeat)
                .doOnCancel(subscription::cancel)
                .doOnTerminate(subscription::cancel);
    }

    private static Set<String> parseFilter(String filter) {
        if (filter == null || filter.isBlank()) return Set.of();
        return Set.of(filter.toLowerCase().split(","));
    }

    private static boolean matchesFilter(SpectorEvent event, Set<String> categories) {
        String eventType = event.eventType(); // e.g., "search.completed"
        String category = eventType.contains(".")
                ? eventType.substring(0, eventType.indexOf('.'))
                : eventType;
        return categories.contains(category);
    }
}
