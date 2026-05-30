package com.spectrayan.spector.node.api.v1;

import java.util.Map;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Param;

import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;
import com.spectrayan.spector.node.exception.LegacySpectorApiException;
import com.spectrayan.spector.node.service.IngestService;

/**
 * Document management API v1 endpoint.
 *
 * <ul>
 *   <li>{@code DELETE /documents/{id}} — delete a document by ID</li>
 * </ul>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class DocumentEndpoint implements ApiModule {

    private final IngestService ingestService;

    public DocumentEndpoint(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public String pathPrefix() { return ""; }

    @Delete("/documents/{id}")
    public HttpResponse delete(@Param("id") String id) {
        boolean deleted = ingestService.delete(id);
        if (!deleted) {
            throw LegacySpectorApiException.notFound("Document not found: " + id);
        }
        return HttpResponse.ofJson(Map.of("id", id, "deleted", true));
    }
}
