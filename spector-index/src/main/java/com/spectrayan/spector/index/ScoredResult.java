package com.spectrayan.spector.index;

import com.spectrayan.spector.commons.valhalla.ValueCandidate;
import com.spectrayan.spector.core.similarity.SimilarityFunction;

/**
 * A scored search result from a vector or keyword index.
 *
 * <p><b>Valhalla (JEP 401):</b> This is a {@code value record} — identity-free,
 * enabling the JVM to flatten {@code ScoredResult[]} arrays into contiguous memory
 * (eliminating 12–16 byte object headers per element) and scalarize short-lived
 * instances into CPU registers during HNSW traversal.</p>
 *
 * @param id    the document/vector identifier
 * @param index the internal integer index in the store
 * @param score the similarity or distance score
 */
@ValueCandidate(reason = "Millions of allocations per HNSW search — the #1 value class candidate",
                hotPathFrequency = ValueCandidate.Frequency.CRITICAL)
public value record ScoredResult(String id, int index, float score) implements Comparable<ScoredResult> {

    /**
     * Compares by score in descending order (highest score first).
     * For distance metrics where lower is better, callers should negate or
     * use {@link #compareAscending}.
     */
    @Override
    public int compareTo(ScoredResult other) {
        return Float.compare(other.score, this.score); // descending
    }

    /**
     * Compares by score ascending (lowest first) — used for distance metrics.
     */
    public static int compareAscending(ScoredResult a, ScoredResult b) {
        return Float.compare(a.score, b.score);
    }
}
