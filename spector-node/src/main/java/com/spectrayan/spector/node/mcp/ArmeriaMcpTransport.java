/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spectrayan.spector.node.mcp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Native Armeria implementation of MCP transports.
 *
 * <p>Supports two transport modes on the same Armeria HTTP stack:</p>
 *
 * <h3>Streamable HTTP (MCP 2025-03-26 â€” recommended)</h3>
 * <p>A single endpoint handles all MCP traffic:</p>
 * <ul>
 *   <li>{@code POST /mcp} â€” JSON-RPC request â†’ JSON response</li>
 *   <li>{@code GET  /mcp} â€” SSE stream for server-initiated notifications (stateful only)</li>
 *   <li>{@code DELETE /mcp} â€” Session termination (stateful only)</li>
 * </ul>
 *
 * <h3>Stateless Mode (recommended for most use cases)</h3>
 * <p>When {@code stateless=true}, no {@code Mcp-Session-Id} header is emitted in
 * responses. Per the MCP spec, this signals clients not to track sessions. A single
 * shared {@link McpServerSession} handles all requests internally. This makes the
 * server resilient to restarts â€” clients never cache a session ID that can go stale.
 * GET and DELETE return 405 since there are no sessions to stream or terminate.</p>
 *
 * <h3>Stateful Mode</h3>
 * <p>Session management via {@code Mcp-Session-Id} header. If a client sends an
 * unknown session ID (e.g., after server restart), a new session is transparently
 * created instead of returning 404.</p>
 */
public class ArmeriaMcpTransport implements McpServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(ArmeriaMcpTransport.class);

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String MESSAGE_EVENT = "message";


    /** Timeout for waiting for a response from session.handle(). */
    private static final long RESPONSE_TIMEOUT_SECONDS = 60;

    private final McpJsonMapper jsonMapper;
    private final boolean stateless;

    // â”€â”€ Shared state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean isClosing = new AtomicBoolean(false);
    private volatile McpServerSession.Factory sessionFactory;

    // â”€â”€ Stateless shared session â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final String SHARED_SESSION_ID = "__stateless__";
    private volatile McpServerSession sharedSession;
    private volatile StreamableSessionTransport sharedTransport;

    // â”€â”€ Streamable HTTP state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private final Map<String, StreamableSessionTransport> streamableTransports = new ConcurrentHashMap<>();


    /**
     * Creates a transport with default settings (stateless mode).
     */
    public ArmeriaMcpTransport() {
        this(true);
    }

    /**
     * Creates a transport with the specified session mode.
     *
     * @param stateless if true, operates in stateless mode (no session tracking,
     *                  no Mcp-Session-Id headers â€” recommended for most use cases)
     */
    public ArmeriaMcpTransport(boolean stateless) {
        this(McpJsonDefaults.getMapper(), stateless);
    }

    /**
     * Creates a transport with custom JSON mapper.
     *
     * @param jsonMapper JSON mapper for serialization
     * @param stateless  if true, operates in stateless mode
     */
    public ArmeriaMcpTransport(McpJsonMapper jsonMapper, boolean stateless) {
        this.jsonMapper = jsonMapper;
        this.stateless = stateless;
    }

    // â”€â”€ McpServerTransportProvider â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
        if (stateless) {
            // Pre-create the shared session for stateless mode.
            // All requests will be dispatched through this single session.
            sharedTransport = new StreamableSessionTransport(SHARED_SESSION_ID);
            sharedSession = sessionFactory.create(sharedTransport);
            sessions.put(SHARED_SESSION_ID, sharedSession);
            streamableTransports.put(SHARED_SESSION_ID, sharedTransport);
            log.info("[MCP] Stateless mode: shared session created");
        }
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params)
                        .onErrorComplete())
                .then();
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return Mono.defer(() -> {
            McpServerSession session = sessions.values().stream()
                    .filter(s -> sessionId.equals(s.getId()))
                    .findFirst()
                    .orElse(null);
            if (session == null) {
                return Mono.empty();
            }
            return session.sendNotification(method, params);
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing.set(true);
        log.info("[MCP] Graceful shutdown: {} active sessions", sessions.size());
        return Flux.fromIterable(sessions.values())
                .flatMap(McpServerSession::closeGracefully)
                .then()
                .doOnSuccess(v -> {
                    // Close all Streamable HTTP notification writers
                    streamableTransports.values().forEach(t -> {
                        HttpResponseWriter w = t.notificationWriter;
                        if (w != null) w.close();
                    });
                    streamableTransports.clear();
                    sessions.clear();
                    log.info("[MCP] Shutdown complete");
                });
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Streamable HTTP Transport (MCP 2025-03-26)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Returns the unified Streamable HTTP service (mount at {@code /mcp}).
     *
     * <p>Handles POST (JSON-RPC request/response), GET (SSE notification stream),
     * and DELETE (session termination) on a single endpoint.</p>
     */
    public HttpService streamableHttpService() {
        return new AbstractHttpService() {

            // â”€â”€ POST /mcp â€” JSON-RPC request â†’ response â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            @Override
            protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                if (isClosing.get()) {
                    return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }
                if (sessionFactory == null) {
                    return jsonError(HttpStatus.INTERNAL_SERVER_ERROR,
                            McpSchema.ErrorCodes.INTERNAL_ERROR,
                            "MCP server not initialized");
                }

                return HttpResponse.of(
                    req.aggregate().thenCompose(agg -> {
                        try {
                            String body = agg.contentUtf8();
                            McpSchema.JSONRPCMessage message =
                                    McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

                            // â”€â”€ Resolve session â”€â”€
                            StreamableSessionTransport transport;
                            McpServerSession session;

                            if (stateless) {
                                // Stateless mode: use the shared session for all requests.
                                // Ignore any Mcp-Session-Id the client may send.
                                transport = sharedTransport;
                                session = sharedSession;
                            } else {
                                // Stateful mode: resolve or create session
                                String sessionId = req.headers().get(SESSION_HEADER);

                                if (sessionId == null || sessionId.isBlank()) {
                                    // New session (first request, typically "initialize")
                                    sessionId = UUID.randomUUID().toString();
                                    transport = new StreamableSessionTransport(sessionId);
                                    session = sessionFactory.create(transport);
                                    sessions.put(sessionId, session);
                                    streamableTransports.put(sessionId, transport);
                                    log.info("[MCP] Streamable HTTP session created: {}", sessionId);
                                } else {
                                    session = sessions.get(sessionId);
                                    transport = streamableTransports.get(sessionId);
                                    if (session == null || transport == null) {
                                        // Recovery: client sent a stale/unknown session ID
                                        // (e.g., after server restart). Create a new session
                                        // transparently instead of returning 404.
                                        String newSessionId = UUID.randomUUID().toString();
                                        transport = new StreamableSessionTransport(newSessionId);
                                        session = sessionFactory.create(transport);
                                        sessions.put(newSessionId, session);
                                        streamableTransports.put(newSessionId, transport);
                                        sessionId = newSessionId;
                                        log.info("[MCP] Recovered stale session â€” new session created: {}",
                                                newSessionId);
                                    }
                                }
                            }

                            // â”€â”€ Determine if this is a request (expects response) â”€â”€
                            boolean isRequest = message instanceof McpSchema.JSONRPCRequest;
                            CompletableFuture<String> responseFuture = null;

                            if (isRequest) {
                                McpSchema.JSONRPCRequest jsonRpcRequest = (McpSchema.JSONRPCRequest) message;
                                responseFuture = new CompletableFuture<>();
                                transport.pendingResponses.put(jsonRpcRequest.id(), responseFuture);
                            }

                            final CompletableFuture<String> rf = responseFuture;

                            // â”€â”€ Handle the message â”€â”€
                            return session.handle(message)
                                    .then(Mono.defer(() -> {
                                        if (rf != null) {
                                            // Wait for the response from sendMessage()
                                            return Mono.fromFuture(rf);
                                        }
                                        // Notification â€” no response expected
                                        return Mono.just("");
                                    }))
                                    .map(responseJson -> {
                                        if (responseJson == null || responseJson.isEmpty()) {
                                            // Notification accepted â€” 202
                                            return HttpResponse.of(
                                                    ResponseHeaders.builder(HttpStatus.ACCEPTED)
                                                            .build());
                                        }
                                        // Request response â€” 200 with JSON body.
                                        // In stateless mode, omit Mcp-Session-Id so clients
                                        // don't cache a session that can go stale.
                                        return HttpResponse.of(
                                                ResponseHeaders.builder(HttpStatus.OK)
                                                        .contentType(MediaType.JSON)
                                                        .build(),
                                                HttpData.ofUtf8(responseJson));
                                    })
                                    .onErrorResume(e -> {
                                        log.error("[MCP] Error handling message: {}",
                                                e.getMessage());
                                        return Mono.just(jsonError(
                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                McpSchema.ErrorCodes.INTERNAL_ERROR,
                                                e.getMessage()));
                                    })
                                    .toFuture();

                        } catch (Exception e) {
                            log.error("[MCP] Error deserializing message: {}", e.getMessage());
                            return CompletableFuture.completedFuture(
                                    jsonError(HttpStatus.BAD_REQUEST,
                                            McpSchema.ErrorCodes.PARSE_ERROR,
                                            "Invalid JSON-RPC message: " + e.getMessage()));
                        }
                    })
                );
            }

            // â”€â”€ GET /mcp â€” SSE notification stream â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            @Override
            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                if (stateless) {
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED,
                            MediaType.PLAIN_TEXT,
                            "SSE streams not available in stateless mode");
                }

                if (isClosing.get()) {
                    return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }

                String sessionId = req.headers().get(SESSION_HEADER);
                if (sessionId == null || sessionId.isBlank()) {
                    return jsonError(HttpStatus.BAD_REQUEST,
                            McpSchema.ErrorCodes.INVALID_REQUEST,
                            "Mcp-Session-Id header required for GET");
                }

                StreamableSessionTransport transport = streamableTransports.get(sessionId);
                if (transport == null) {
                    return jsonError(HttpStatus.NOT_FOUND,
                            McpSchema.ErrorCodes.INTERNAL_ERROR,
                            "Session not found: " + sessionId);
                }

                // Open SSE stream for server â†’ client notifications
                HttpResponseWriter writer = HttpResponse.streaming();
                writer.write(ResponseHeaders.of(HttpStatus.OK,
                        "Content-Type", "text/event-stream",
                        "Cache-Control", "no-cache",
                        "Connection", "keep-alive",
                        SESSION_HEADER, sessionId));

                transport.notificationWriter = writer;
                log.info("[MCP] Notification stream opened for session {}", sessionId);

                writer.whenComplete().thenRun(() -> {
                    transport.notificationWriter = null;
                    log.info("[MCP] Notification stream closed for session {}", sessionId);
                });

                return writer;
            }

            // â”€â”€ DELETE /mcp â€” Session termination â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            @Override
            protected HttpResponse doDelete(ServiceRequestContext ctx, HttpRequest req) {
                if (stateless) {
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED,
                            MediaType.PLAIN_TEXT,
                            "Session termination not available in stateless mode");
                }

                String sessionId = req.headers().get(SESSION_HEADER);
                if (sessionId == null || sessionId.isBlank()) {
                    return jsonError(HttpStatus.BAD_REQUEST,
                            McpSchema.ErrorCodes.INVALID_REQUEST,
                            "Mcp-Session-Id header required for DELETE");
                }

                McpServerSession session = sessions.remove(sessionId);
                StreamableSessionTransport transport = streamableTransports.remove(sessionId);

                if (session != null) {
                    session.closeGracefully().subscribe();
                }
                if (transport != null) {
                    HttpResponseWriter w = transport.notificationWriter;
                    if (w != null) w.close();
                }

                log.info("[MCP] Session terminated: {}", sessionId);
                return HttpResponse.of(HttpStatus.OK);
            }
        };
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void sendSseEvent(HttpResponseWriter writer, String eventType, String data) {
        String sseFrame = "event: " + eventType + "\ndata: " + data + "\n\n";
        writer.write(HttpData.ofUtf8(sseFrame));
    }

    private HttpResponse jsonError(HttpStatus status, int errorCode, String message) {
        try {
            var errorBody = Map.of(
                    "jsonrpc", "2.0",
                    "error", Map.of("code", errorCode, "message", message));
            String json = jsonMapper.writeValueAsString(errorBody);
            return HttpResponse.of(status, MediaType.JSON, json);
        } catch (Exception e) {
            return HttpResponse.of(status, MediaType.PLAIN_TEXT, message);
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    //  Session Transport
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Per-session transport for Streamable HTTP.
     *
     * <p>Responses to requests are captured via {@link #pendingResponses}
     * and returned in the POST response body. Server-initiated notifications
     * are pushed via the optional SSE {@link #notificationWriter}.</p>
     */
    private class StreamableSessionTransport implements McpServerTransport {

        private final String sessionId;
        final Map<Object, CompletableFuture<String>> pendingResponses = new ConcurrentHashMap<>();
        volatile HttpResponseWriter notificationWriter;

        StreamableSessionTransport(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    String json = jsonMapper.writeValueAsString(message);

                    // Route responses to the pending POST request
                    if (message instanceof McpSchema.JSONRPCResponse response) {
                        CompletableFuture<String> future = pendingResponses.remove(response.id());
                        if (future != null) {
                            future.complete(json);
                            return;
                        }
                        log.warn("[MCP] No pending request for response id={} in session {}",
                                response.id(), sessionId);
                    }

                    // Server-initiated notifications â†’ SSE stream
                    HttpResponseWriter writer = notificationWriter;
                    if (writer != null) {
                        sendSseEvent(writer, MESSAGE_EVENT, json);
                        log.debug("[MCP] Notification sent to session {}", sessionId);
                    } else {
                        log.debug("[MCP] No notification stream for session {}, message buffered/dropped",
                                sessionId);
                    }
                } catch (Exception e) {
                    log.error("[MCP] Failed to send message to session {}: {}",
                            sessionId, e.getMessage());
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                sessions.remove(sessionId);
                streamableTransports.remove(sessionId);
                // Cancel any pending responses
                pendingResponses.values().forEach(f ->
                        f.completeExceptionally(new RuntimeException("Session closed")));
                pendingResponses.clear();
                HttpResponseWriter writer = notificationWriter;
                if (writer != null) {
                    writer.close();
                    notificationWriter = null;
                }
                log.debug("[MCP] Streamable session closed: {}", sessionId);
            });
        }

        @Override
        public void close() {
            closeGracefully().subscribe();
        }
    }
}

