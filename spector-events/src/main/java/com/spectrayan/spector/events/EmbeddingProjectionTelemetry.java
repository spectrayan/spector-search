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
package com.spectrayan.spector.events;

import java.util.List;

/**
 * Embedding projection telemetry — carries PCA/random-projected 3D
 * coordinates of stored vectors for the vector space visualization.
 *
 * @param points          projected 3D points for stored vectors
 * @param queryProjection projected 3D coordinates of the query vector (nullable)
 */
public record EmbeddingProjectionTelemetry(
        List<ProjectedPoint> points,
        float[] queryProjection
) implements TelemetryEvent {

    /**
     * A single vector projected into 3D space.
     *
     * @param id         memory/document ID
     * @param x          projected X coordinate
     * @param y          projected Y coordinate
     * @param z          projected Z coordinate
     * @param tier       memory tier ("WORKING", "EPISODIC", etc.)
     * @param importance importance score (0.0–1.0)
     * @param label      human-readable label
     */
    public record ProjectedPoint(
            String id,
            float x, float y, float z,
            String tier,
            float importance,
            String label
    ) {}
}
