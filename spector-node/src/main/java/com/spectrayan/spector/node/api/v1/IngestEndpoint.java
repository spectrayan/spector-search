package com.spectrayan.spector.node.api.v1;

import java.util.Map;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Post;

import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.api.dto.BulkIngestRequest;
import com.spectrayan.spector.node.api.dto.IngestRequest;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;
import com.spectrayan.spector.node.service.IngestService;

/**
 * Ingest API v1 endpoint.
 *
 * <ul>
 *   <li>{@code POST /ingest}      — ingest with pre-computed vector</li>
 *   <li>{@code POST /ingest/auto} — ingest with auto-embedding</li>
 *   <li>{@code POST /ingest/bulk} — batch ingest multiple documents</li>
 * </ul>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class IngestEndpoint implements ApiModule {

    private final IngestService ingestService;

    public IngestEndpoint(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public String pathPrefix() { return ""; }

    @Post("/ingest")
    public HttpResponse ingest(IngestRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        ingestService.ingest(request);
        return HttpResponse.ofJson(HttpStatus.CREATED,
                Map.of("id", request.id, "indexed", true));
    }

    @Post("/ingest/auto")
    public HttpResponse autoIngest(IngestRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        ingestService.autoIngest(request);
        return HttpResponse.ofJson(HttpStatus.CREATED,
                Map.of("id", request.id, "indexed", true, "autoEmbedded", true));
    }

    @Post("/ingest/bulk")
    public HttpResponse bulkIngest(BulkIngestRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        int[] result = ingestService.bulkIngest(request);
        return HttpResponse.ofJson(HttpStatus.CREATED,
                Map.of("total", result[0], "success", result[1], "failed", result[2]));
    }
}
