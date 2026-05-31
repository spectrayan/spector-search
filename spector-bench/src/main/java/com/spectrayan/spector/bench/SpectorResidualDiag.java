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
 * Minimal diagnostic: verifies residual L2 = original L2.
 */
public class SpectorResidualDiag {

    public static void main(String[] args) {
        int dims = 128;
        int N = 100;
        int nCentroids = 4;
        Random rng = new Random(42L);

        // Generate vectors
        float[][] vectors = new float[N][];
        for (int i = 0; i < N; i++) vectors[i] = gaussianUnit(rng, dims);

        // Train KMeans
        float[][] centroids = KMeans.train(vectors, nCentroids, 25, 42L);

        // For each vector, verify residual identity
        float[] query = gaussianUnit(new Random(999L), dims);
        int qCentroid = KMeans.nearestCentroid(query, centroids);
        System.out.printf("Query nearest centroid: %d%n%n", qCentroid);

        // Check a few vectors
        for (int i = 0; i < 10; i++) {
            int xCentroid = KMeans.nearestCentroid(vectors[i], centroids);

            // Original L2
            float origL2sq = 0;
            for (int d = 0; d < dims; d++) {
                float diff = query[d] - vectors[i][d];
                origL2sq += diff * diff;
            }
            float origL2 = (float) Math.sqrt(origL2sq);

            // Residual L2 (using x's centroid)
            float[] resQ = new float[dims];
            float[] resX = new float[dims];
            float[] cx = centroids[xCentroid];
            for (int d = 0; d < dims; d++) {
                resQ[d] = query[d] - cx[d];
                resX[d] = vectors[i][d] - cx[d];
            }
            float resL2sq = 0;
            for (int d = 0; d < dims; d++) {
                float diff = resQ[d] - resX[d];
                resL2sq += diff * diff;
            }
            float resL2 = (float) Math.sqrt(resL2sq);

            // SIMD L2
            float simdL2 = SimilarityFunction.EUCLIDEAN.compute(resQ, resX);

            System.out.printf("Vec %d (centroid %d): origL2=%.8f  resL2=%.8f  simdL2=%.8f  match=%b%n",
                    i, xCentroid, origL2, resL2, simdL2,
                    Math.abs(origL2 - resL2) < 0.0001);
        }

        // Now test actual SpectorIndex search
        System.out.println("\n--- SpectorIndex Search ---");
        SpectorIndex index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(nCentroids)
                .nProbe(nCentroids) // ALL
                .shardThreshold(20_000)
                .oversamplingFactor(10) // high oversampling
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        index.train(vectors);
        for (int i = 0; i < N; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        ScoredResult[] results = index.search(query, 10);
        System.out.println("Top-10 from SpectorIndex:");
        for (ScoredResult r : results) {
            int idx = r.index();
            float origL2 = 0;
            for (int d = 0; d < dims; d++) {
                float diff = query[d] - vectors[idx][d];
                origL2 += diff * diff;
            }
            origL2 = (float) Math.sqrt(origL2);
            System.out.printf("  doc-%d: spectorScore=%.8f  origL2=%.8f  ratio=%.4f%n",
                    idx, r.score(), origL2, origL2 / r.score());
        }

        index.close();
    }

    private static float[] gaussianUnit(Random rng, int dims) {
        float[] v = new float[dims];
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = (float) rng.nextGaussian();
            norm += (double) v[i] * v[i];
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < dims; i++) v[i] *= scale;
        return v;
    }
}
