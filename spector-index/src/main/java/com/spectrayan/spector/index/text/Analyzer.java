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
