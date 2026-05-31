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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for NoOpEntityExtractor.
 */
class NoOpEntityExtractorTest {

    @Test
    void extractReturnsEmpty() {
        List<ExtractedEntity> result = NoOpEntityExtractor.INSTANCE.extract("id", "some text");
        assertThat(result).isEmpty();
    }

    @Test
    void isAvailableReturnsTrue() {
        assertThat(NoOpEntityExtractor.INSTANCE.isAvailable()).isTrue();
    }

    @Test
    void singletonInstance() {
        assertThat(NoOpEntityExtractor.INSTANCE).isSameAs(NoOpEntityExtractor.INSTANCE);
    }
}
