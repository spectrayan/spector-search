package com.spectrayan.spector.node.api.v1;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Post;

import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.api.dto.RagRequest;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;
import com.spectrayan.spector.node.service.RagService;

/**
 * RAG (Retrieval-Augmented Generation) API v1 endpoint.
 *
 * <ul>
 *   <li>{@code POST /rag} — retrieve context with attributions</li>
 * </ul>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class RagEndpoint implements ApiModule {

    private final RagService ragService;

    public RagEndpoint(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String pathPrefix() { return ""; }

    @Post("/rag")
    public HttpResponse rag(RagRequest request) throws com.spectrayan.spector.commons.error.SpectorException {
        return HttpResponse.ofJson(ragService.retrieveContext(request));
    }
}
