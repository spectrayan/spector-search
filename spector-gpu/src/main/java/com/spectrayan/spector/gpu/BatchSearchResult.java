package com.spectrayan.spector.gpu;

import com.spectrayan.spector.commons.valhalla.ValueCandidate;

/**
 * A scored search result from a batch GPU search operation.
 *
 * <p><b>Valhalla (JEP 401):</b> This is a {@code value record} — only 8 bytes of payload
 * (int + float). As a value type, arrays are flattened to contiguous int-float pairs
 * with zero object headers, ideal for GPU result buffer processing.</p>
 *
 * @param vectorIndex the index of the matched vector in the database
 * @param score       the similarity score (higher is more similar)
 */
@ValueCandidate(reason = "Tiny 8-byte payload, bulk-allocated during GPU batch similarity",
                hotPathFrequency = ValueCandidate.Frequency.CRITICAL)
public value record BatchSearchResult(int vectorIndex, float score) implements Comparable<BatchSearchResult> {

    /**
     * Compares by score in descending order (highest score first).
     */
    @Override
    public int compareTo(BatchSearchResult other) {
        return Float.compare(other.score, this.score); // descending
    }
}
