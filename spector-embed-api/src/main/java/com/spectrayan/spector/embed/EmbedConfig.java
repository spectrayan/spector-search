package com.spectrayan.spector.embed;

import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.error.SpectorValidationException;

/**
 * Configuration for the parallel embedding pipeline.
 *
 * @param batchSize  number of chunks to embed per batch (must be &gt; 0)
 * @param maxRetries maximum number of retry attempts for a failed batch (must be &gt;= 0)
 */
public record EmbedConfig(int batchSize, int maxRetries) {

    /** Default configuration: batch size 32, 3 retries. */
    public static final EmbedConfig DEFAULT = new EmbedConfig(32, 3);

    public EmbedConfig {
        if (batchSize <= 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "batchSize", 1, Integer.MAX_VALUE, batchSize);
        }
        if (maxRetries < 0) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NEGATIVE, "maxRetries", maxRetries);
        }
    }
}
