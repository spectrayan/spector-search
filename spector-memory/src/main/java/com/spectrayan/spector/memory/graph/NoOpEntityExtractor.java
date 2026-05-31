/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.graph;

import java.util.List;

/**
 * No-op entity extractor that returns an empty list.
 *
 * <p>Used when entity extraction is disabled ({@link EntityExtractionMode#NONE}).
 * All calls to {@link #extract} return immediately with no overhead.</p>
 */
public final class NoOpEntityExtractor implements EntityExtractor {

    /** Singleton instance. */
    public static final NoOpEntityExtractor INSTANCE = new NoOpEntityExtractor();

    private NoOpEntityExtractor() {}

    @Override
    public List<ExtractedEntity> extract(String id, String text) {
        return List.of();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
