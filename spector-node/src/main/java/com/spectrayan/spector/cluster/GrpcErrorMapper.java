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
package com.spectrayan.spector.cluster;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Code;
import com.spectrayan.spector.commons.error.*;
import com.spectrayan.spector.cluster.error.SpectorShardUnavailableException;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for mapping between Spector exceptions and rich gRPC error details.
 *
 * <p>Implements the Google/gRPC Rich Error Model by embedding `com.google.rpc.ErrorInfo`
 * inside the protobuf-based `google.rpc.Status` message. This allows transparent error
 * propagation across distributed search shards.</p>
 */
public final class GrpcErrorMapper {

    private static final String ERROR_DOMAIN = "spector";

    /**
     * Translates a Throwable into a rich gRPC StatusRuntimeException.
     *
     * @param e the exception to map
     * @return rich StatusRuntimeException
     */
    public static StatusRuntimeException toStatusRuntimeException(Throwable e) {
        if (e instanceof StatusRuntimeException sre) {
            return sre;
        }

        Code grpcCode = Code.INTERNAL;
        String codeId = "SPE-900-001"; // Default internal error code
        String category = "Internal";
        String message = e.getMessage() != null ? e.getMessage() : "An unexpected error occurred";

        if (e instanceof SpectorException se) {
            ErrorCode ec = se.errorCode();
            codeId = se.codeId();
            category = ec.category().displayName();
            grpcCode = mapCategoryToGrpcCode(ec.category(), ec);
        }

        // Build the rich ErrorInfo protobuf details
        ErrorInfo errorInfo = ErrorInfo.newBuilder()
                .setReason(codeId)
                .setDomain(ERROR_DOMAIN)
                .putMetadata("category", category)
                .putMetadata("timestamp", Instant.now().toString())
                .build();

        // Build the google.rpc.Status message
        com.google.rpc.Status status = com.google.rpc.Status.newBuilder()
                .setCode(grpcCode.getNumber())
                .setMessage(message)
                .addDetails(Any.pack(errorInfo))
                .build();

        return StatusProto.toStatusRuntimeException(status);
    }

    /**
     * Extracts a rich gRPC Status from a Throwable and reconstructs the matching SpectorException.
     *
     * @param e       the exception caught on the client side
     * @param shardId the ID of the shard node that was called
     * @return the reconstructed SpectorException subclass, or a generic SpectorShardUnavailableException
     */
    public static SpectorException toSpectorException(Throwable e, String shardId) {
        if (e instanceof StatusRuntimeException sre) {
            com.google.rpc.Status status = StatusProto.fromThrowable(sre);
            if (status != null) {
                for (Any any : status.getDetailsList()) {
                    if (any.is(ErrorInfo.class)) {
                        try {
                            ErrorInfo errorInfo = any.unpack(ErrorInfo.class);
                            String codeId = errorInfo.getReason();
                            ErrorCode ec = ErrorCode.fromId(codeId);
                            if (ec != null) {
                                String message = status.getMessage();
                                return instantiateCategoryException(ec, message, shardId);
                            }
                        } catch (Exception ex) {
                            // Fallback to generic parsing below
                        }
                    }
                }
            }
        }

        if (e instanceof SpectorException se) {
            return se;
        }

        return new SpectorShardUnavailableException(shardId, e);
    }

    private static SpectorException instantiateCategoryException(ErrorCode ec, String message, String shardId) {
        switch (ec.category()) {
            case VALIDATION:
                return new SpectorValidationException(ec, message, true);
            case CONFIG:
                return new SpectorConfigException(ec, message, true);
            case INDEX:
                return new SpectorIndexException(ec, message, true);
            case STORAGE:
                return new SpectorStorageException(ec, message, true);
            case EMBEDDING:
                return new SpectorEmbeddingException(ec, message, true);
            case MEMORY:
                return new SpectorMemoryException(ec, message, true);
            case GPU:
                return new SpectorGpuException(ec, message, true);
            case SERVER:
                return new SpectorServerException(ec, message, true);
            case CLIENT:
                return new SpectorClientException(ec, message, true);
            case INGESTION:
                return new SpectorIngestionException(ec, message, true);
            case CLUSTER:
                if (ec == ErrorCode.SHARD_UNAVAILABLE) {
                    return new SpectorShardUnavailableException(shardId);
                }
                return new SpectorClusterException(ec, message, true);
            case INTERNAL:
            default:
                return new SpectorInternalException(ec, message, true);
        }
    }

    private static Code mapCategoryToGrpcCode(ErrorCategory cat, ErrorCode ec) {
        switch (cat) {
            case VALIDATION:
                return Code.INVALID_ARGUMENT;
            case CONFIG:
                return Code.FAILED_PRECONDITION;
            case INDEX:
                if (ec == ErrorCode.INDEX_READ_ONLY) return Code.FAILED_PRECONDITION;
                if (ec == ErrorCode.INDEX_FULL) return Code.RESOURCE_EXHAUSTED;
                return Code.INTERNAL;
            case STORAGE:
                if (ec == ErrorCode.STORE_FULL) return Code.RESOURCE_EXHAUSTED;
                if (ec == ErrorCode.SEGMENT_CLOSED) return Code.FAILED_PRECONDITION;
                return Code.INTERNAL;
            case EMBEDDING:
                if (ec == ErrorCode.EMBEDDING_UNAVAILABLE) return Code.UNAVAILABLE;
                if (ec == ErrorCode.EMBEDDING_TIMEOUT) return Code.DEADLINE_EXCEEDED;
                return Code.INTERNAL;
            case MEMORY:
                if (ec == ErrorCode.MEMORY_TIER_FULL) return Code.RESOURCE_EXHAUSTED;
                return Code.INTERNAL;
            case GPU:
                if (ec == ErrorCode.GPU_MEMORY_EXHAUSTED || ec == ErrorCode.GPU_BUDGET_EXCEEDED) {
                    return Code.RESOURCE_EXHAUSTED;
                }
                return Code.INTERNAL;
            case SERVER:
                if (ec == ErrorCode.API_UNAUTHORIZED) return Code.UNAUTHENTICATED;
                if (ec == ErrorCode.API_NOT_FOUND) return Code.NOT_FOUND;
                if (ec == ErrorCode.API_CONFLICT) return Code.ALREADY_EXISTS;
                if (ec == ErrorCode.API_SERVICE_UNAVAILABLE) return Code.UNAVAILABLE;
                return Code.INTERNAL;
            case CLIENT:
                if (ec == ErrorCode.CLIENT_CONNECTION_FAILED) return Code.UNAVAILABLE;
                if (ec == ErrorCode.CLIENT_TIMEOUT) return Code.DEADLINE_EXCEEDED;
                return Code.INTERNAL;
            case INGESTION:
                if (ec == ErrorCode.INGESTION_FORMAT_UNSUPPORTED) return Code.INVALID_ARGUMENT;
                return Code.INTERNAL;
            case CLUSTER:
                return Code.UNAVAILABLE;
            default:
                return Code.INTERNAL;
        }
    }

    private GrpcErrorMapper() {}
}
