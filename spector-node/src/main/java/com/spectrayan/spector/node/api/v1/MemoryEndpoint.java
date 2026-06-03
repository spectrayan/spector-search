package com.spectrayan.spector.node.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;

import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;
import com.spectrayan.spector.node.api.ApiModule;
import com.spectrayan.spector.node.api.dto.IntrospectRequest;
import com.spectrayan.spector.node.api.dto.IntrospectResponseDto;
import com.spectrayan.spector.node.api.dto.MemoryRequest;
import com.spectrayan.spector.node.api.dto.RecallRequest;
import com.spectrayan.spector.node.api.dto.RecallResponseDto;
import com.spectrayan.spector.node.api.dto.ReflectResponseDto;
import com.spectrayan.spector.node.api.dto.ReminderRequest;
import com.spectrayan.spector.node.api.dto.ReminderResponseDto;
import com.spectrayan.spector.node.api.dto.ScratchpadRequest;
import com.spectrayan.spector.node.api.dto.WhyNotRequest;
import com.spectrayan.spector.node.api.dto.WhyNotResponseDto;
import com.spectrayan.spector.node.exception.ApiExceptionHandler;
import com.spectrayan.spector.node.service.MemoryService;

import java.time.Duration;

/**
 * Armeria endpoints for cognitive memory v1 REST API.
 *
 * <p>Registered under {@code /api/v1/memory}. All mutating endpoints are
 * thread-safe and operate asynchronously on virtual threads via
 * {@link MemoryService}.</p>
 */
@ExceptionHandler(ApiExceptionHandler.class)
public class MemoryEndpoint implements ApiModule {

    private final MemoryService memoryService;

    public MemoryEndpoint(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String pathPrefix() {
        return "/memory";
    }

    @Post("/remember")
    public HttpResponse remember(MemoryRequest request) {
        MemoryType tier = MemoryType.valueOf(request.effectiveTier());
        MemorySource source = MemorySource.valueOf(request.effectiveSource());
        IngestionHints hints = null;
        if (request.hasCognitiveHints()) {
            float interest = request.interest() != null ? request.interest() : 0f;
            float challenge = request.challenge() != null ? request.challenge() : 0f;
            float urgency = request.urgency() != null ? request.urgency() : 0f;
            int valence = request.valence() != null ? request.valence() : 0;
            int arousal = request.arousal() != null ? request.arousal() : 0;
            hints = new IngestionHints(interest, challenge, urgency,
                    (byte) Math.clamp(valence, -128, 127),
                    (byte) Math.clamp(arousal, 0, 255));
        }

        memoryService.remember(request.id(), request.text(), tier, source, hints, request.tagsArray()).join();
        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Stored cognitive memory: " + request.id());
    }

    @Post("/recall")
    public HttpResponse recall(RecallRequest request) {
        long start = System.currentTimeMillis();
        var profile = com.spectrayan.spector.memory.CognitiveProfile.valueOf(request.effectiveProfile());
        var builder = RecallOptions.builder().profile(profile).topK(request.effectiveTopK());
        if (request.queryValence() != null) {
            builder.queryValence((byte) Math.clamp(request.queryValence(), -128, 127));
        }
        var options = builder.build();

        var results = memoryService.recall(request.query(), options);
        long elapsed = System.currentTimeMillis() - start;

        var response = new RecallResponseDto(
                results,
                memoryService.memory().totalMemories(),
                elapsed,
                request.effectiveProfile()
        );
        return HttpResponse.ofJson(response);
    }

    @Delete("/{id}")
    public HttpResponse forget(@Param("id") String id) {
        memoryService.forget(id);
        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Forgotten memory: " + id);
    }

    @Post("/{id}/reinforce")
    public HttpResponse reinforce(@Param("id") String id, JsonNode body) {
        int valence = body.has("valence") ? body.get("valence").asInt() : 0;
        memoryService.reinforce(id, (byte) Math.clamp(valence, -128, 127));
        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Reinforced memory: " + id + " with valence " + valence);
    }

    @Post("/{id}/suppress")
    public HttpResponse suppress(@Param("id") String id, JsonNode body) {
        String action = body.has("action") ? body.get("action").asText().toUpperCase() : "SUPPRESS";
        String reason = body.has("reason") ? body.get("reason").asText() : "";
        if ("UNSUPPRESS".equals(action)) {
            memoryService.unsuppress(id);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Unsuppressed memory: " + id);
        } else {
            memoryService.suppress(id, reason);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Suppressed memory: " + id + (reason.isEmpty() ? "" : " (reason: " + reason + ")"));
        }
    }

    @Post("/{id}/resolve")
    public HttpResponse resolve(@Param("id") String id, JsonNode body) {
        boolean resolved = body.has("resolved") ? body.get("resolved").asBoolean() : true;
        if (resolved) {
            memoryService.markResolved(id);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Resolved memory: " + id);
        } else {
            memoryService.markUnresolved(id);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Unresolved memory: " + id);
        }
    }

    @Get("/status")
    public HttpResponse status() {
        var status = memoryService.getStatus();
        return HttpResponse.ofJson(status);
    }

    // ── New endpoints (API parity with MCP tools) ───────────────────────

    @Post("/introspect")
    public HttpResponse introspect(IntrospectRequest request) {
        var insight = memoryService.introspect(request.topic());
        return HttpResponse.ofJson(IntrospectResponseDto.from(insight));
    }

    @Post("/reminder")
    public HttpResponse reminder(ReminderRequest request) {
        var reminder = memoryService.scheduleReminder(
                request.text(),
                Duration.ofSeconds(request.delaySeconds()),
                request.tagsArray());
        return HttpResponse.ofJson(ReminderResponseDto.from(reminder, request.delaySeconds()));
    }

    @Post("/scratchpad")
    public HttpResponse scratchpad(ScratchpadRequest request) {
        memoryService.scratchpad(request.text()).join();
        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                "Stored in working memory scratchpad");
    }

    @Post("/why-not")
    public HttpResponse whyNot(WhyNotRequest request) {
        var options = RecallOptions.builder().topK(request.effectiveTopK()).build();
        var explanation = memoryService.whyNot(request.memoryId(), request.query(), options);
        return HttpResponse.ofJson(WhyNotResponseDto.from(explanation));
    }

    @Post("/reflect")
    public HttpResponse reflect() {
        var report = memoryService.reflect();
        return HttpResponse.ofJson(ReflectResponseDto.from(report));
    }
}
