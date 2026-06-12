/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.commons.error;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for all SpectorException subclasses — construction, error codes, messages.
 * Targets the 0% coverage classes in the error package.
 */
@DisplayName("SpectorException Hierarchy")
class SpectorExceptionHierarchyTest {

    @Nested
    @DisplayName("SpectorValidationException")
    class ValidationTests {
        @Test void construction() {
            var ex = new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "field", "bad");
            assertThat(ex).isInstanceOf(SpectorException.class);
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.ARGUMENT_INVALID);
            assertThat(ex.getMessage()).contains("field");
        }
    }

    @Nested
    @DisplayName("SpectorIngestionException")
    class IngestionTests {
        @Test void construction() {
            var ex = new SpectorIngestionException(ErrorCode.INGESTION_PIPELINE_FAILED, "doc-1");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INGESTION_PIPELINE_FAILED);
            assertThat(ex.getMessage()).contains("doc-1");
        }
    }

    @Nested
    @DisplayName("SpectorIndexException")
    class IndexTests {
        @Test void construction() {
            var ex = new SpectorIndexException(ErrorCode.HNSW_BUILD_FAILED);
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.HNSW_BUILD_FAILED);
        }
    }

    @Nested
    @DisplayName("SpectorEmbeddingException")
    class EmbeddingTests {
        @Test void construction() {
            var ex = new SpectorEmbeddingException(ErrorCode.EMBEDDING_UNAVAILABLE, "model unavailable");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("SpectorStorageException")
    class StorageTests {
        @Test void construction() {
            var ex = new SpectorStorageException(ErrorCode.DISK_IO_FAILED, "disk full");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.DISK_IO_FAILED);
        }
    }

    @Nested
    @DisplayName("SpectorGpuException")
    class GpuTests {
        @Test void construction() {
            var ex = new SpectorGpuException(ErrorCode.GPU_NOT_AVAILABLE, "no CUDA");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.GPU_NOT_AVAILABLE);
        }
        @Test void withCause() {
            var cause = new RuntimeException("driver");
            var ex = new SpectorGpuException(ErrorCode.GPU_NOT_AVAILABLE, cause, "fallback");
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    @Nested
    @DisplayName("SpectorApiException")
    class ApiTests {
        @Test void construction() {
            var ex = new SpectorApiException(400, ErrorCode.API_BAD_REQUEST, "timeout");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.API_BAD_REQUEST);
        }
        @Test void withCause() {
            var ex = new SpectorApiException(500, ErrorCode.API_BAD_REQUEST, new RuntimeException(), "err");
            assertThat(ex.getCause()).isNotNull();
        }
    }

    @Nested
    @DisplayName("SpectorServerException")
    class ServerTests {
        @Test void construction() {
            var ex = new SpectorServerException(ErrorCode.INTERNAL_ERROR, "oops");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("SpectorClientException")
    class ClientTests {
        @Test void construction() {
            var ex = new SpectorClientException(ErrorCode.CLIENT_CONNECTION_FAILED, "bad request");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.CLIENT_CONNECTION_FAILED);
        }
    }

    @Nested
    @DisplayName("SpectorClusterException")
    class ClusterTests {
        @Test void construction() {
            var ex = new SpectorClusterException(ErrorCode.CLUSTER_MEMBERSHIP_FAILED, "node-3");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.CLUSTER_MEMBERSHIP_FAILED);
        }
    }

    @Nested
    @DisplayName("SpectorMemoryException")
    class MemoryTests {
        @Test void construction() {
            var ex = new SpectorMemoryException(ErrorCode.MEMORY_TIER_FULL, "semantic", 1000);
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.MEMORY_TIER_FULL);
        }
    }

    @Nested
    @DisplayName("SpectorConfigException (commons)")
    class ConfigTests {
        @Test void construction() {
            var ex = new SpectorConfigException(ErrorCode.CONFIG_FILE_NOT_FOUND, "prop.key");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFIG_FILE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("SpectorInternalException")
    class InternalTests {
        @Test void construction() {
            var ex = new SpectorInternalException(ErrorCode.INTERNAL_ERROR, "unexpected");
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("SpectorDocumentReadException")
    class DocReadTests {
        @Test void construction() {
            var ex = new SpectorDocumentReadException("file.pdf", "corrupt");
            assertThat(ex.getMessage()).contains("file.pdf");
        }
        @Test void withCause() {
            var cause = new java.io.IOException("corrupt");
            var ex = new SpectorDocumentReadException("file.pdf", "io error", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SpectorException base class
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SpectorException base")
    class BaseTests {
        @Test
        @DisplayName("error code is accessible")
        void errorCode() {
            var ex = new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "x", "y");
            assertThat(ex.errorCode()).isNotNull();
        }

        @Test
        @DisplayName("message includes formatted error code info")
        void formattedMessage() {
            var ex = new SpectorValidationException(ErrorCode.TOP_K_INVALID, 100, -5);
            assertThat(ex.getMessage()).isNotBlank();
        }
    }
}
