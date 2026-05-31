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

import com.google.rpc.Code;
import com.spectrayan.spector.commons.error.*;
import com.spectrayan.spector.cluster.error.SpectorShardUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GrpcErrorMapper}.
 */
class GrpcErrorMapperTest {

    @Test
    void shouldMapValidationExceptionToInvalidArgumentStatusRuntimeException() {
        var cause = new SpectorValidationException(ErrorCode.DIMENSIONS_MISMATCH, 384, 768);
        StatusRuntimeException sre = GrpcErrorMapper.toStatusRuntimeException(cause);

        assertNotNull(sre);
        com.google.rpc.Status status = StatusProto.fromThrowable(sre);
        assertNotNull(status);

        assertEquals(Code.INVALID_ARGUMENT.getNumber(), status.getCode());
        assertTrue(status.getMessage().contains("SPE-100-002"));
        assertTrue(status.getMessage().contains("Expected 384 dimensions but received 768"));

        assertEquals(1, status.getDetailsCount());
    }

    @Test
    void shouldMapGenericExceptionToInternalStatusRuntimeException() {
        var cause = new RuntimeException("DB offline");
        StatusRuntimeException sre = GrpcErrorMapper.toStatusRuntimeException(cause);

        assertNotNull(sre);
        com.google.rpc.Status status = StatusProto.fromThrowable(sre);
        assertNotNull(status);

        assertEquals(Code.INTERNAL.getNumber(), status.getCode());
        assertEquals("DB offline", status.getMessage());
    }

    @Test
    void shouldReconstructConcreteExceptionOnClientSide() {
        var original = new SpectorValidationException(ErrorCode.TOP_K_INVALID, 10, 0);
        StatusRuntimeException sre = GrpcErrorMapper.toStatusRuntimeException(original);

        SpectorException reconstructed = GrpcErrorMapper.toSpectorException(sre, "shard-1");

        assertNotNull(reconstructed);
        assertTrue(reconstructed instanceof SpectorValidationException, "Expected SpectorValidationException class");
        assertEquals(ErrorCode.TOP_K_INVALID, reconstructed.errorCode());
        assertEquals(original.getMessage(), reconstructed.getMessage(), "Message should be perfectly preserved");
    }

    @Test
    void shouldReconstructStorageExceptionOnClientSide() {
        var original = new SpectorStorageException(ErrorCode.STORE_FULL, "index-1");
        StatusRuntimeException sre = GrpcErrorMapper.toStatusRuntimeException(original);

        SpectorException reconstructed = GrpcErrorMapper.toSpectorException(sre, "shard-1");

        assertNotNull(reconstructed);
        assertTrue(reconstructed instanceof SpectorStorageException, "Expected SpectorStorageException class");
        assertEquals(ErrorCode.STORE_FULL, reconstructed.errorCode());
        assertEquals(original.getMessage(), reconstructed.getMessage());
    }

    @Test
    void shouldFallbackToSpectorShardUnavailableExceptionForGenericGrpcErrors() {
        StatusRuntimeException sre = io.grpc.Status.UNAVAILABLE
                .withDescription("Connection refused")
                .asRuntimeException();

        SpectorException reconstructed = GrpcErrorMapper.toSpectorException(sre, "shard-9");

        assertNotNull(reconstructed);
        assertTrue(reconstructed instanceof SpectorShardUnavailableException, "Expected SpectorShardUnavailableException fallback");
        assertEquals("shard-9", ((SpectorShardUnavailableException) reconstructed).getShardId());
    }
}
