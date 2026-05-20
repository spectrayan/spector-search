package com.spectrayan.spector.index;

import java.util.List;

/**
 * Transforms raw text into a list of indexable terms.
 *
 * <p>Analyzers form a pipeline: tokenize → lowercase → filter stop words → stem.
 * Custom analyzers can be plugged in for domain-specific text processing.</p>
 */
public interface Analyzer {

    /**
     * Analyzes the input text and returns a list of terms.
     *
     * @param text the raw input text
     * @return list of processed terms (may contain duplicates for TF counting)
     */
    List<String> analyze(String text);
}
