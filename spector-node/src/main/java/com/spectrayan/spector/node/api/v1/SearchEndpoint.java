package com.spectrayan.spector.node.api.v1;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesEventStream;

import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.api.dto.SearchRequest;
import com.spectrayan.spector.node.api.dto.SearchResponseDto;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;
import com.spectrayan.spector.node.service.SearchService;
import com.spectrayan.spector.query.SearchQuery;
import com.spectrayan.spector.query.SearchResponse;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Search API v1 endpoint.
 *
 * <ul>
 *   <li>{@code POST /search} — keyword/vector/hybrid search</li>
 *   <li>{@code GET /search/stream} — streaming search via SSE</li>
 * </ul>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class SearchEndpoint implements ApiModule {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SearchService searchService;

    public SearchEndpoint(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String pathPrefix() { return ""; }

    @Post("/search")
    public HttpResponse search(SearchRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        SearchResponseDto response = searchService.search(request);
        return HttpResponse.ofJson(response);
    }

    @Get("/search/stream")
    @ProducesEventStream
    public Publisher<ServerSentEvent> streamSearch(
            @Param("text") String text,
            @Param("topK") int topK) {

        int k = topK > 0 ? topK : 10;
        SearchQuery query = SearchQuery.keyword(text, k);
        SearchResponse response = searchService.searchRaw(query);
        ScoredResult[] results = response.results();

        return Flux.create(sink -> {
            try {
                for (int i = 0; i < results.length; i++) {
                    ScoredResult r = results[i];
                    String data = MAPPER.writeValueAsString(Map.of(
                            "id", r.id(), "score", r.score(), "rank", i + 1));
                    sink.next(ServerSentEvent.builder().event("result").data(data).build());
                }
                String doneData = MAPPER.writeValueAsString(Map.of(
                        "totalHits", response.totalHits(),
                        "queryTimeMs", response.queryTimeMs(),
                        "mode", response.mode().name()));
                sink.next(ServerSentEvent.builder().event("done").data(doneData).build());
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
}
