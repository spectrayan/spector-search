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
package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.cluster.KMeans;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import java.util.*;

/**
 * Targeted diagnostic: 4 vectors, 2 dimensions, completely traceable.
 */
public class SpectorTinyDiag {

    public static void main(String[] args) {
        int dims = 4;  // tiny for manual verification
        int N = 8;
        int nCentroids = 2;

        // Manually defined vectors for full traceability
        float[][] vectors = {
            {1.0f, 0.0f, 0.0f, 0.0f},  // doc-0
            {0.9f, 0.1f, 0.0f, 0.0f},  // doc-1
            {0.0f, 1.0f, 0.0f, 0.0f},  // doc-2
            {0.0f, 0.9f, 0.1f, 0.0f},  // doc-3
            {0.5f, 0.5f, 0.0f, 0.0f},  // doc-4
            {-1.0f, 0.0f, 0.0f, 0.0f}, // doc-5
            {0.0f, 0.0f, 1.0f, 0.0f},  // doc-6
            {0.0f, 0.0f, 0.0f, 1.0f},  // doc-7
        };

        float[] query = {0.8f, 0.2f, 0.0f, 0.0f};

        // Print brute-force L2 from query to each vector
        System.out.println("=== Brute-Force L2 Distances ===");
        for (int i = 0; i < N; i++) {
            float l2sq = 0;
            for (int d = 0; d < dims; d++) {
                float diff = query[d] - vectors[i][d];
                l2sq += diff * diff;
            }
            float l2 = (float) Math.sqrt(l2sq);
            System.out.printf("  doc-%d: L2=%.6f  L2²=%.6f  vec=%s%n",
                    i, l2, l2sq, Arrays.toString(vectors[i]));
        }

        // Build SpectorIndex
        System.out.println("\n=== SpectorIndex ===");
        SpectorIndex index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(nCentroids)
                .nProbe(nCentroids) // ALL
                .shardThreshold(20_000)
                .oversamplingFactor(10)
                .similarityFunction(SimilarityFunction.COSINE) // user chose cosine
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        index.train(vectors);

        // Show centroid assignments
        float[][] centroids = KMeans.train(vectors, nCentroids, 25, 42L);
        System.out.println("Centroids from separate KMeans:");
        for (int c = 0; c < nCentroids; c++) {
            System.out.printf("  c%d = %s%n", c, Arrays.toString(centroids[c]));
        }

        // Show shard sizes from index
        for (int i = 0; i < N; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }
        int[] shardSizes = index.shardSizes();
        System.out.println("Shard sizes: " + Arrays.toString(shardSizes));

        // Search
        System.out.println("\nSearch results:");
        ScoredResult[] results = index.search(query, N);
        for (ScoredResult r : results) {
            int idx = r.index();
            float origL2sq = 0;
            for (int d = 0; d < dims; d++) {
                float diff = query[d] - vectors[idx][d];
                origL2sq += diff * diff;
            }
            float origL2 = (float) Math.sqrt(origL2sq);
            System.out.printf("  doc-%d: spectorL2=%.6f  origL2=%.6f  match=%b  vec=%s%n",
                    idx, r.score(), origL2, Math.abs(r.score() - origL2) < 0.001,
                    Arrays.toString(vectors[idx]));
        }

        index.close();
    }
}
