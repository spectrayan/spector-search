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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for loading and caching classpath resources.
 *
 * <h3>Usage</h3>
 * <pre>
 *   String prompt = ResourceUtils.loadResource("prompts/entity-extraction.txt");
 *   // Subsequent calls return the cached copy — zero I/O.
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>All methods are thread-safe. The internal cache uses {@link ConcurrentHashMap}
 * and resources are loaded at most once per JVM lifetime.</p>
 *
 * <h3>Cache Policy</h3>
 * <p>Resources are cached permanently (process-scoped). This is appropriate for
 * prompt templates, SQL schemas, and other static classpath resources that never
 * change at runtime. Call {@link #clearCache()} or {@link #evict(String)} to
 * force a reload (e.g., during testing).</p>
 */
public final class ResourceUtils {

    private static final Logger log = LoggerFactory.getLogger(ResourceUtils.class);

    /** Process-scoped cache: resourcePath → contents. */
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private ResourceUtils() {} // utility class — no instances

    /**
     * Loads a classpath resource as a UTF-8 string, caching the result.
     *
     * <p>The resource is looked up via the current thread's context class loader,
     * falling back to {@code ResourceUtils.class} class loader.</p>
     *
     * @param resourcePath classpath path (e.g. "prompts/entity-extraction.txt")
     * @return the resource contents as a string
     * @throws IllegalArgumentException if the resource is not found
     */
    public static String loadResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        return CACHE.computeIfAbsent(resourcePath, ResourceUtils::doLoad);
    }

    /**
     * Loads a classpath resource as a UTF-8 string, returning a default value
     * if the resource is not found.
     *
     * @param resourcePath classpath path
     * @param defaultValue value to return if the resource is not found
     * @return the resource contents or the default value
     */
    public static String loadResourceOrDefault(String resourcePath, String defaultValue) {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        try {
            return CACHE.computeIfAbsent(resourcePath, ResourceUtils::doLoad);
        } catch (IllegalArgumentException e) {
            log.debug("Resource not found '{}', using default", resourcePath);
            return defaultValue;
        }
    }

    /**
     * Loads a classpath resource as raw bytes without caching.
     *
     * @param resourcePath classpath path
     * @return the resource bytes
     * @throws IllegalArgumentException if the resource is not found
     * @throws IOException if an I/O error occurs
     */
    public static byte[] loadResourceBytes(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        try (InputStream is = openStream(resourcePath)) {
            return is.readAllBytes();
        }
    }

    /**
     * Checks whether a classpath resource exists.
     *
     * @param resourcePath classpath path
     * @return true if the resource exists
     */
    public static boolean exists(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null && cl.getResource(resourcePath) != null) return true;
        return ResourceUtils.class.getClassLoader().getResource(resourcePath) != null;
    }

    /**
     * Evicts a single resource from the cache, forcing a reload on next access.
     *
     * @param resourcePath classpath path to evict
     */
    public static void evict(String resourcePath) {
        CACHE.remove(resourcePath);
    }

    /**
     * Clears the entire resource cache.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Returns the number of cached resources.
     */
    public static int cacheSize() {
        return CACHE.size();
    }

    // ── Internal ──

    private static String doLoad(String resourcePath) {
        try (InputStream is = openStream(resourcePath)) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Loaded and cached resource '{}' ({} bytes)", resourcePath, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to read classpath resource: " + resourcePath, e);
        }
    }

    private static InputStream openStream(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream is = cl != null ? cl.getResourceAsStream(resourcePath) : null;
        if (is == null) {
            is = ResourceUtils.class.getClassLoader().getResourceAsStream(resourcePath);
        }
        if (is == null) {
            throw new IllegalArgumentException(
                    "Classpath resource not found: " + resourcePath);
        }
        return is;
    }
}
