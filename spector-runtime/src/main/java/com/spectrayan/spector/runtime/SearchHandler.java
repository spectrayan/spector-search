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
package com.spectrayan.spector.runtime;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.config.SpectorMode;
import com.spectrayan.spector.engine.SpectorEngine;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.memory.CognitiveResult;
import com.spectrayan.spector.memory.RecallOptions;
import com.spectrayan.spector.memory.SpectorMemory;
import com.spectrayan.spector.query.SearchResponse;

/**
 * Mode-aware search service.
 *
 * <p>Routes search queries to the engine or cognitive memory based on
 * the global {@link SpectorMode}. Produces unified {@link SpectorResult}
 * objects regardless of the underlying source.</p>
 *
 * <p>Obtained via {@code runtime.search()}. Not instantiated directly.</p>
 */
public final class SearchHandler {

    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);

    private final SpectorEngine engine;
    private final SpectorMemory memory;  // nullable
    private final SpectorMode mode;

    SearchHandler(SpectorEngine engine, SpectorMemory memory, SpectorMode mode) {
        this.engine = engine;
        this.memory = memory;
        this.mode = mode;
    }

    /**
     * Executes a mode-aware search.
     *
     * <p>In SEARCH mode, performs hybrid (BM25 + vector) search via the engine.
     * In MEMORY mode, queries cognitive memory with decay and importance scoring.</p>
     *
     * @param text query text
     * @param topK maximum results to return
     * @return list of unified results
     */
    public List<SpectorResult> query(String text, int topK) {
        if (mode == SpectorMode.MEMORY && memory != null) {
            return queryMemory(text, topK);
        }
        return queryEngine(text, topK);
    }

    /**
     * Searches the engine directly, bypassing mode routing.
     */
    public SearchResponse queryEngine(String text, int topK, boolean raw) {
        return engine.search(text, topK);
    }

    private List<SpectorResult> queryEngine(String text, int topK) {
        SearchResponse response = engine.search(text, topK);
        return Arrays.stream(response.results())
                .map(sr -> SpectorResult.fromSearch(sr.id(), "", sr.score(), sr.score()))
                .toList();
    }

    private List<SpectorResult> queryMemory(String text, int topK) {
        var options = RecallOptions.builder().topK(topK).build();
        List<CognitiveResult> results = memory.recall(text, options);
        return results.stream()
                .map(r -> SpectorResult.fromMemory(
                        r.id(), r.text(), r.score(),
                        r.importance(), r.ageDays(),
                        r.valence(), r.synapticTags(), r.memoryType()))
                .toList();
    }
}
