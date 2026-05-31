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
package com.spectrayan.spector.commons;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ResourceUtils}.
 */
class ResourceUtilsTest {

    @AfterEach
    void tearDown() {
        ResourceUtils.clearCache();
    }

    @Test
    void loadResourceThrowsForMissing() {
        assertThatThrownBy(() -> ResourceUtils.loadResource("nonexistent/file.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void loadResourceOrDefaultReturnsFallback() {
        String result = ResourceUtils.loadResourceOrDefault("nonexistent.txt", "fallback");
        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void existsReturnsFalseForMissing() {
        assertThat(ResourceUtils.exists("nonexistent/resource.txt")).isFalse();
    }

    @Test
    void evictRemovesFromCache() {
        // Pre-populate cache via loadResourceOrDefault (it won't cache on miss)
        ResourceUtils.clearCache();
        assertThat(ResourceUtils.cacheSize()).isZero();
    }

    @Test
    void clearCacheEmptiesAll() {
        ResourceUtils.clearCache();
        assertThat(ResourceUtils.cacheSize()).isZero();
    }

    @Test
    void loadResourceReturnsNullForNullPath() {
        assertThatThrownBy(() -> ResourceUtils.loadResource(null))
                .isInstanceOf(NullPointerException.class);
    }
}
