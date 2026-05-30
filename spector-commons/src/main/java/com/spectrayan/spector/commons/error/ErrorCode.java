package com.spectrayan.spector.commons.error;

import java.util.HashMap;
import java.util.Map;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorException;

/**
 * Central registry of all Spector error codes.
 *
 * <p>Each error code follows the {@code SPE-XXX-YYY} schema where {@code XXX} is the
 * category prefix and {@code YYY} is the specific error within that category.
 * Internally, codes are stored as a single integer (e.g. {@code 100_001} for
 * {@code SPE-100-001}).</p>
 *
 * <h3>Stability Guarantee</h3>
 * <p>Error codes are <b>immutable once assigned</b>. If an error is deprecated, it is
 * marked {@code @Deprecated} but never reassigned or removed. Users can safely build
 * automation, monitoring alerts, and support workflows on these codes.</p>
 *
 * <h3>Message Templates</h3>
 * <p>Each code carries an SLF4J-style message template with {@code {}} placeholders.
 * Use {@link #format(Object...)} to produce the final message:
 * <pre>{@code
 *   ErrorCode.DIMENSIONS_MISMATCH.format(384, 768)
 *   // → "[SPE-100-002] Expected 384 dimensions but received 768"
 * }</pre>
 *
 * @see ErrorCategory
 * @see SpectorException
 */
public enum ErrorCode {

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATION (SPE-100-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** Vector dimensions must be a positive integer. */
    DIMENSIONS_INVALID        (100_001, ErrorCategory.VALIDATION,
            "Vector dimensions must be positive, got {}"),

    /** Vector dimensions do not match the index configuration. */
    DIMENSIONS_MISMATCH       (100_002, ErrorCategory.VALIDATION,
            "Expected {} dimensions but received {}"),

    /** A required vector argument was null. */
    VECTOR_NULL               (100_003, ErrorCategory.VALIDATION,
            "Vector must not be null"),

    /** Vector array length does not match the expected dimension count. */
    VECTOR_LENGTH_MISMATCH    (100_004, ErrorCategory.VALIDATION,
            "Vector length {} does not match expected {}"),

    /** The top-K parameter is out of valid range. */
    TOP_K_INVALID             (100_005, ErrorCategory.VALIDATION,
            "top_k must be between 1 and {}, got {}"),

    /** Document ID was null or empty. */
    DOCUMENT_ID_NULL          (100_006, ErrorCategory.VALIDATION,
            "Document ID must not be null or empty"),

    /** A required argument was null. */
    ARGUMENT_NULL             (100_007, ErrorCategory.VALIDATION,
            "{} must not be null"),

    /** An argument value is outside its valid range. */
    ARGUMENT_OUT_OF_RANGE     (100_008, ErrorCategory.VALIDATION,
            "{} must be between {} and {}, got {}"),

    /** An unsupported quantization type was specified. */
    QUANTIZATION_TYPE_INVALID (100_009, ErrorCategory.VALIDATION,
            "Unsupported quantization type: {}"),

    /** A capacity limit has been exceeded. */
    CAPACITY_EXCEEDED         (100_010, ErrorCategory.VALIDATION,
            "Capacity exceeded: max={}, requested={}"),

    /** SimilarityFunction argument was null. */
    SIMILARITY_FUNCTION_NULL  (100_011, ErrorCategory.VALIDATION,
            "SimilarityFunction must not be null"),

    /** A required collection was null or empty. */
    EMPTY_COLLECTION          (100_012, ErrorCategory.VALIDATION,
            "{} must not be empty"),

    /** A general argument value is invalid. */
    ARGUMENT_INVALID          (100_013, ErrorCategory.VALIDATION,
            "Invalid value for {}: {}"),

    /** A numeric argument must be non-negative. */
    ARGUMENT_NEGATIVE         (100_014, ErrorCategory.VALIDATION,
            "{} must be non-negative, got {}"),

    /** Two arrays or segments must have equal length. */
    LENGTH_MISMATCH           (100_015, ErrorCategory.VALIDATION,
            "{} length {} does not match {} length {}"),

    /** Bit width is not one of the supported values. */
    BIT_WIDTH_INVALID         (100_016, ErrorCategory.VALIDATION,
            "Bit width must be one of {}, got {}"),

    /** The engine has been closed and cannot accept further operations. */
    ENGINE_CLOSED             (100_017, ErrorCategory.VALIDATION,
            "SpectorEngine is closed"),

    /** An embedding provider is required but was not configured. */
    EMBEDDING_PROVIDER_MISSING(100_018, ErrorCategory.VALIDATION,
            "No EmbeddingProvider configured — use builder().embeddingProvider() or supply vectors manually"),

    // ══════════════════════════════════════════════════════════════════════
    // CONFIG (SPE-110-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** Configuration file could not be found at the specified path. */
    CONFIG_FILE_NOT_FOUND     (110_001, ErrorCategory.CONFIG,
            "Configuration file not found: {}"),

    /** Configuration file exists but could not be parsed. */
    CONFIG_PARSE_FAILED       (110_002, ErrorCategory.CONFIG,
            "Failed to parse configuration: {}"),

    /** A configuration value is invalid or out of range. */
    CONFIG_VALUE_INVALID      (110_003, ErrorCategory.CONFIG,
            "Invalid configuration value for {}: {}"),

    /** A named configuration profile was not found. */
    CONFIG_PROFILE_NOT_FOUND  (110_004, ErrorCategory.CONFIG,
            "Configuration profile not found: {}"),

    /** A required configuration key is missing. */
    CONFIG_REQUIRED_MISSING   (110_005, ErrorCategory.CONFIG,
            "Required configuration key missing: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // INDEX (SPE-200-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** Parallel HNSW index construction failed. */
    HNSW_BUILD_FAILED         (200_001, ErrorCategory.INDEX,
            "HNSW index construction failed"),

    /** HNSW graph structural integrity check detected corruption. */
    HNSW_GRAPH_CORRUPTED      (200_002, ErrorCategory.INDEX,
            "HNSW graph integrity check failed: {}"),

    /** Index has reached its maximum document capacity. */
    INDEX_FULL                (200_003, ErrorCategory.INDEX,
            "Index has reached maximum capacity: {}"),

    /** Index is in read-only mode and cannot accept writes. */
    INDEX_READ_ONLY           (200_004, ErrorCategory.INDEX,
            "Index is read-only, write operations not permitted"),

    /** IVF centroid training failed during calibration. */
    IVF_TRAINING_FAILED       (200_005, ErrorCategory.INDEX,
            "IVF centroid training failed: {}"),

    /** BM25 text tokenization encountered an error. */
    BM25_TOKENIZATION_FAILED  (200_006, ErrorCategory.INDEX,
            "BM25 text tokenization failed: {}"),

    /** Index could not be serialized to persistent storage. */
    INDEX_SERIALIZATION_FAILED(200_007, ErrorCategory.INDEX,
            "Index serialization to disk failed: {}"),

    /** Index could not be loaded from persistent storage. */
    INDEX_LOAD_FAILED         (200_008, ErrorCategory.INDEX,
            "Index deserialization from disk failed: {}"),

    /** Operation requires a trained index, but train() has not been called. */
    INDEX_NOT_TRAINED         (200_009, ErrorCategory.INDEX,
            "Index not trained, call train() before search"),

    /** Centroid count for IVF must be a positive integer. */
    CENTROID_COUNT_INVALID    (200_010, ErrorCategory.INDEX,
            "Centroid count must be positive, got {}"),

    /** HNSW graph connectivity is below the required threshold. */
    HNSW_CONNECTIVITY_LOW     (200_011, ErrorCategory.INDEX,
            "HNSW graph connectivity below threshold: {} < {}"),

    // ══════════════════════════════════════════════════════════════════════
    // STORAGE (SPE-210-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** Attempted operation on a closed memory segment. */
    SEGMENT_CLOSED            (210_001, ErrorCategory.STORAGE,
            "Memory segment is closed"),

    /** Failed to create a memory-mapped file. */
    MMAP_FAILED               (210_002, ErrorCategory.STORAGE,
            "Memory-mapped file creation failed: {}"),

    /** Vector store has reached its configured capacity. */
    STORE_FULL                (210_003, ErrorCategory.STORAGE,
            "Vector store has reached capacity: {}"),

    /** A disk I/O operation failed (read, write, or sync). */
    DISK_IO_FAILED            (210_004, ErrorCategory.STORAGE,
            "Disk I/O operation failed: {}"),

    /** Write-ahead log entry could not be written. */
    WAL_WRITE_FAILED          (210_005, ErrorCategory.STORAGE,
            "Write-ahead log write failed"),

    /** Write-ahead log replay encountered an error. */
    WAL_REPLAY_FAILED         (210_006, ErrorCategory.STORAGE,
            "Write-ahead log replay failed: {}"),

    /** Vector store has not been initialized. */
    STORE_NOT_INITIALIZED     (210_007, ErrorCategory.STORAGE,
            "Vector store not initialized"),

    /** Persistent index file has an unrecognized format or version. */
    FILE_FORMAT_INVALID       (210_008, ErrorCategory.STORAGE,
            "Invalid index file format: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // EMBEDDING (SPE-300-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** Embedding provider (e.g. Ollama) is not reachable. */
    EMBEDDING_UNAVAILABLE     (300_001, ErrorCategory.EMBEDDING,
            "Embedding provider is unavailable: {}"),

    /** Embedding request returned an error response. */
    EMBEDDING_REQUEST_FAILED  (300_002, ErrorCategory.EMBEDDING,
            "Embedding request failed: {}"),

    /** Embedding request exceeded the configured timeout. */
    EMBEDDING_TIMEOUT         (300_003, ErrorCategory.EMBEDDING,
            "Embedding request timed out after {}ms"),

    /** The requested embedding model was not found. */
    EMBEDDING_MODEL_NOT_FOUND (300_004, ErrorCategory.EMBEDDING,
            "Embedding model not found: {}"),

    /** Embedding provider returned vectors with unexpected dimensions. */
    EMBEDDING_DIM_MISMATCH    (300_005, ErrorCategory.EMBEDDING,
            "Embedding returned {} dims, expected {}"),

    // ══════════════════════════════════════════════════════════════════════
    // MEMORY (SPE-310-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** A cognitive memory tier has reached its capacity limit. */
    MEMORY_TIER_FULL          (310_001, ErrorCategory.MEMORY,
            "Memory tier {} has reached capacity: {}"),

    /** The cognitive recall pipeline encountered a failure. */
    MEMORY_RECALL_FAILED      (310_002, ErrorCategory.MEMORY,
            "Cognitive recall pipeline failed"),

    /** Memory consolidation process failed. */
    MEMORY_CONSOLIDATION_FAILED(310_003, ErrorCategory.MEMORY,
            "Memory consolidation failed: {}"),

    /** The specified memory ID does not exist. */
    MEMORY_ID_NOT_FOUND       (310_004, ErrorCategory.MEMORY,
            "Memory ID not found: {}"),

    /** Memory WAL file is corrupted or unreadable. */
    MEMORY_WAL_CORRUPTED      (310_005, ErrorCategory.MEMORY,
            "Memory WAL file corrupted: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // GPU (SPE-400-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** CUDA driver library could not be located on the system. */
    CUDA_DRIVER_NOT_FOUND     (400_001, ErrorCategory.GPU,
            "CUDA driver not found"),

    /** GPU memory allocation failed — not enough device memory. */
    GPU_MEMORY_EXHAUSTED      (400_002, ErrorCategory.GPU,
            "GPU memory allocation failed: requested={}B, available={}B"),

    /** A GPU compute kernel failed to launch or execute. */
    GPU_KERNEL_LAUNCH_FAILED  (400_003, ErrorCategory.GPU,
            "GPU kernel launch failed: {}"),

    /** The GPU device reported a hardware or driver error. */
    GPU_DEVICE_ERROR          (400_004, ErrorCategory.GPU,
            "GPU device error: {}"),

    /** The allocation would exceed the configured GPU memory budget. */
    GPU_BUDGET_EXCEEDED       (400_005, ErrorCategory.GPU,
            "GPU memory budget exceeded: requested={}B, budget={}B"),

    /** GPU is not available on this system. */
    GPU_NOT_AVAILABLE         (400_006, ErrorCategory.GPU,
            "GPU is not available: {}"),

    /** GPU memory allocation failed. */
    GPU_MEMORY_ALLOC_FAILED   (400_007, ErrorCategory.GPU,
            "GPU memory allocation failed: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // SERVER (SPE-500-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** HTTP 400 — client sent a malformed or invalid request. */
    API_BAD_REQUEST           (500_001, ErrorCategory.SERVER,
            "Bad request: {}"),

    /** HTTP 404 — the requested resource does not exist. */
    API_NOT_FOUND             (500_002, ErrorCategory.SERVER,
            "Resource not found: {}"),

    /** HTTP 409 — resource state conflict (e.g. duplicate ID). */
    API_CONFLICT              (500_003, ErrorCategory.SERVER,
            "Resource conflict: {}"),

    /** HTTP 401/403 — invalid or missing API key. */
    API_UNAUTHORIZED          (500_004, ErrorCategory.SERVER,
            "Unauthorized: invalid or missing API key"),

    /** HTTP 503 — a required backend service is unavailable. */
    API_SERVICE_UNAVAILABLE   (500_005, ErrorCategory.SERVER,
            "Service unavailable: {}"),

    /** An MCP tool handler encountered a failure during execution. */
    MCP_TOOL_FAILED           (500_006, ErrorCategory.SERVER,
            "MCP tool execution failed: {}"),

    /** gRPC transport-level error during inter-node communication. */
    GRPC_TRANSPORT_FAILED     (500_007, ErrorCategory.SERVER,
            "gRPC transport error: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // CLIENT (SPE-510-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** Failed to establish a connection to the Spector server. */
    CLIENT_CONNECTION_FAILED  (510_001, ErrorCategory.CLIENT,
            "Failed to connect to Spector server: {}"),

    /** A client request exceeded the configured timeout. */
    CLIENT_TIMEOUT            (510_002, ErrorCategory.CLIENT,
            "Client request timed out after {}ms"),

    /** The server returned a response that could not be parsed. */
    CLIENT_RESPONSE_INVALID   (510_003, ErrorCategory.CLIENT,
            "Invalid server response: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // INGESTION (SPE-600-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** The document format is not supported by any registered parser. */
    INGESTION_FORMAT_UNSUPPORTED(600_001, ErrorCategory.INGESTION,
            "Unsupported document format: {}"),

    /** A document could not be read or processed. */
    DOCUMENT_READ_FAILED      (600_004, ErrorCategory.INGESTION,
            "Failed to read document '{}': {}"),

    /** Document content chunking failed. */
    INGESTION_CHUNKING_FAILED (600_002, ErrorCategory.INGESTION,
            "Document chunking failed: {}"),

    /** The ingestion pipeline encountered a fatal error. */
    INGESTION_PIPELINE_FAILED (600_003, ErrorCategory.INGESTION,
            "Ingestion pipeline failed: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // CLUSTER (SPE-700-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** A target shard is not reachable or has been decommissioned. */
    SHARD_UNAVAILABLE         (700_001, ErrorCategory.CLUSTER,
            "Shard is unavailable: {}"),

    /** A cluster membership operation (join, leave, heartbeat) failed. */
    CLUSTER_MEMBERSHIP_FAILED (700_002, ErrorCategory.CLUSTER,
            "Cluster membership operation failed: {}"),

    /** A query could not be routed to the appropriate shard. */
    CLUSTER_ROUTING_FAILED    (700_003, ErrorCategory.CLUSTER,
            "Request routing failed: {}"),

    // ══════════════════════════════════════════════════════════════════════
    // INTERNAL (SPE-900-xxx)
    // ══════════════════════════════════════════════════════════════════════

    /** An unexpected internal error occurred — likely a bug. */
    INTERNAL_ERROR            (900_001, ErrorCategory.INTERNAL,
            "Internal error: {}"),

    /** An internal invariant or assertion was violated — this is a bug. */
    INVARIANT_VIOLATED        (900_002, ErrorCategory.INTERNAL,
            "Internal invariant violated: {}"),

    /** Execution reached a code path that should be unreachable — this is a bug. */
    UNREACHABLE_CODE          (900_003, ErrorCategory.INTERNAL,
            "Reached unreachable code path: {}"),

    /** A concurrent execution subtask failed. */
    CONCURRENT_EXECUTION_FAILED(900_004, ErrorCategory.INTERNAL,
            "Concurrent execution failed: {}");

    // ══════════════════════════════════════════════════════════════════════

    private final int code;
    private final ErrorCategory category;
    private final String messageTemplate;

    ErrorCode(int code, ErrorCategory category, String messageTemplate) {
        this.code = code;
        this.category = category;
        this.messageTemplate = messageTemplate;
    }

    /** The full numeric code, e.g. {@code 100001}. */
    public int code() {
        return code;
    }

    /** The error category this code belongs to. */
    public ErrorCategory category() {
        return category;
    }

    /** The raw message template with {@code {}} placeholders. */
    public String messageTemplate() {
        return messageTemplate;
    }

    /**
     * Returns the formatted error ID, e.g. {@code "SPE-100-001"}.
     *
     * @return the stable string identifier for this error code
     */
    public String id() {
        return String.format("SPE-%03d-%03d", code / 1000, code % 1000);
    }

    /**
     * Formats the message template by replacing {@code {}} placeholders
     * left-to-right with the provided arguments (SLF4J style).
     *
     * <p>The returned string includes the error code prefix:
     * <pre>{@code
     *   ErrorCode.DIMENSIONS_MISMATCH.format(384, 768)
     *   // → "[SPE-100-002] Expected 384 dimensions but received 768"
     * }</pre>
     *
     * @param args values to substitute for {@code {}} placeholders
     * @return formatted message with error code prefix
     */
    public String format(Object... args) {
        StringBuilder sb = new StringBuilder(messageTemplate.length() + 32);
        sb.append('[').append(id()).append("] ");

        int argIndex = 0;
        int start = 0;
        int idx;
        while ((idx = messageTemplate.indexOf("{}", start)) >= 0) {
            sb.append(messageTemplate, start, idx);
            if (argIndex < args.length) {
                sb.append(args[argIndex++]);
            } else {
                sb.append("{}");
            }
            start = idx + 2;
        }
        sb.append(messageTemplate, start, messageTemplate.length());
        return sb.toString();
    }

    // ────────────────────── Lookup ──────────────────────

    private static final Map<Integer, ErrorCode> BY_CODE;
    static {
        ErrorCode[] values = values();
        BY_CODE = HashMap.newHashMap(values.length);
        for (ErrorCode ec : values) {
            if (BY_CODE.put(ec.code, ec) != null) {
                throw new ExceptionInInitializerError(
                        "Duplicate ErrorCode: " + ec.code + " (" + ec.name() + ")");
            }
        }
    }

    /**
     * Looks up an error code by its numeric value.
     *
     * @param code the numeric code, e.g. {@code 100001}
     * @return the matching {@link ErrorCode}, or {@code null} if not found
     */
    public static ErrorCode fromCode(int code) {
        return BY_CODE.get(code);
    }

    /**
     * Looks up an error code by its string ID, e.g. {@code "SPE-100-001"}.
     *
     * @param id the formatted code string (case-insensitive prefix)
     * @return the matching {@link ErrorCode}, or {@code null} if malformed or not found
     */
    public static ErrorCode fromId(String id) {
        if (id == null || id.length() < 11) {
            return null;
        }
        try {
            // Parse "SPE-XXX-YYY" → category * 1000 + specific
            String normalized = id.toUpperCase();
            if (!normalized.startsWith("SPE-")) {
                return null;
            }
            int dashPos = normalized.indexOf('-', 4);
            if (dashPos < 0) {
                return null;
            }
            int categoryPrefix = Integer.parseInt(normalized.substring(4, dashPos));
            int specific = Integer.parseInt(normalized.substring(dashPos + 1));
            return fromCode(categoryPrefix * 1000 + specific);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
