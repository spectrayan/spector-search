package com.spectrayan.spector.rag;

import com.spectrayan.spector.commons.TextChunk;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;
import com.spectrayan.spector.commons.valhalla.ValueCandidate;

/**
 * A text chunk annotated with a relevance score from search.
 *
 * <p><b>Valhalla (JEP 401):</b> As a {@code value record}, arrays of scored chunks
 * can be heap-flattened during RAG pipeline processing.</p>
 *
 * @param chunk the text chunk
 * @param score relevance score (higher is more relevant)
 */
@ValueCandidate(reason = "Short-lived per-result allocation in RAG pipeline",
                hotPathFrequency = ValueCandidate.Frequency.HIGH)
public value record ScoredChunk(TextChunk chunk, float score) {

    public ScoredChunk {
        if (chunk == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "chunk");
        }
        if (Float.isNaN(score)) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "score", "NaN");
        }
    }
}
