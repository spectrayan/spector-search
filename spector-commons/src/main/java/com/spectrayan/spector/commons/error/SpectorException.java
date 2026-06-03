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
package com.spectrayan.spector.commons.error;

/**
 * Abstract base for all Spector exceptions.
 *
 * <p>Every Spector exception carries an {@link ErrorCode} that uniquely identifies the
 * error condition. The error code follows the {@code SPE-XXX-YYY} schema and is
 * <b>immutable once assigned</b> — users and monitoring systems can safely key on it.</p>
 *
 * <h3>Exception Hierarchy</h3>
 * <pre>{@code
 *   SpectorException
 *   ├── SpectorValidationException      (SPE-100-xxx)
 *   ├── SpectorConfigException          (SPE-110-xxx)
 *   ├── SpectorIndexException           (SPE-200-xxx)
 *   ├── SpectorStorageException         (SPE-210-xxx)
 *   │   ├── SpectorWalCorruptionException   (SPE-310-005)
 *   │   ├── SPE-210-009  PARTITION_DIR_FAILED
 *   │   ├── SPE-210-010  STORAGE_MIGRATION_FAILED
 *   │   └── SPE-210-011  FILE_RENAME_FAILED
 *   ├── SpectorEmbeddingException       (SPE-300-xxx)
 *   ├── SpectorMemoryException          (SPE-310-xxx)
 *   │   ├── SpectorGraphException           (SPE-310-006..011)
 *   │   │   ├── SpectorHebbianException         (SPE-310-006)
 *   │   │   ├── SpectorTemporalChainException   (SPE-310-007)
 *   │   │   ├── SpectorEntityGraphException     (SPE-310-008)
 *   │   │   ├── SpectorCoActivationException    (SPE-310-009)
 *   │   │   ├── SpectorGraphPersistenceException(SPE-310-010)
 *   │   │   └── SpectorGraphDecayException      (SPE-310-011)
 *   │   ├── SpectorMemoryRecallException    (SPE-310-002)
 *   │   ├── SpectorMemoryConsolidationException (SPE-310-003)
 *   │   ├── SpectorMemoryTierFullException  (SPE-310-001)
 *   │   └── SPE-310-013  PARTITION_INDEX_INVALID
 *   ├── SpectorGpuException             (SPE-400-xxx)
 *   ├── SpectorServerException          (SPE-500-xxx)
 *   ├── SpectorClientException          (SPE-510-xxx)
 *   ├── SpectorIngestionException       (SPE-600-xxx)
 *   ├── SpectorClusterException         (SPE-700-xxx)
 *   │   └── SPE-700-004  PARTITION_REPLICATION_FAILED
 *   └── SpectorInternalException        (SPE-900-xxx)
 * }</pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   throw new SpectorValidationException(
 *       ErrorCode.DIMENSIONS_MISMATCH, 384, 768);
 *   // Message: "[SPE-100-002] Expected 384 dimensions but received 768"
 * }</pre>
 *
 * @see ErrorCode
 * @see ErrorCategory
 */
public abstract class SpectorException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Creates a new Spector exception with formatted message.
     *
     * @param errorCode the stable error code identifying this condition
     * @param args      values to substitute into the message template's {@code {}} placeholders
     */
    protected SpectorException(ErrorCode errorCode, Object... args) {
        super(errorCode.format(args));
        this.errorCode = errorCode;
    }

    /**
     * Internal constructor to reconstruct an exception on the client-side
     * with a pre-formatted message, bypassing template formatting.
     */
    protected SpectorException(ErrorCode errorCode, String preformattedMessage, boolean isPreformatted) {
        super(preformattedMessage);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new Spector exception with a cause and formatted message.
     *
     * @param errorCode the stable error code identifying this condition
     * @param cause     the underlying exception that triggered this error
     * @param args      values to substitute into the message template's {@code {}} placeholders
     */
    protected SpectorException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.format(args), cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable error code for this exception.
     *
     * <p>Error codes are immutable once assigned and follow the {@code SPE-XXX-YYY}
     * schema. Users can safely build automation and alerts on these codes.</p>
     *
     * @return the error code, never null
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Returns the numeric error code for programmatic matching.
     *
     * @return the raw numeric code, e.g. {@code 100002}
     */
    public int code() {
        return errorCode.code();
    }

    /**
     * Returns the formatted error code string, e.g. {@code "SPE-100-002"}.
     *
     * @return the stable string identifier
     */
    public String codeId() {
        return errorCode.id();
    }

    /**
     * Returns the error category for broad classification.
     *
     * @return the category this error belongs to
     */
    public ErrorCategory category() {
        return errorCode.category();
    }
}
