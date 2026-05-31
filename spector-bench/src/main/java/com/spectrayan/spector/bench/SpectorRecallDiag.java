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

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.spectrum.SpectorIndex;

import java.util.*;

/**
 * Diagnostic: verifies recall@10 at nProbe=ALL (should be 1.0).
 */
public class SpectorRecallDiag {

    public static void main(String[] args) {
        int dims = 128;
        int N = 1000;  // small dataset for fast debugging
        int nCentroids = 8;
        int k = 10;
        Random rng = new Random(42L);

        // Generate vectors
        float[][] vectors = new float[N][];
        for (int i = 0; i < N; i++) vectors[i] = gaussianUnit(rng, dims);

        // Build index with nProbe=ALL
        SpectorIndex index = SpectorIndex.builder()
                .dimensions(dims)
                .nCentroids(nCentroids)
                .nProbe(nCentroids)  // ALL centroids
                .shardThreshold(20_000)
                .oversamplingFactor(3)
                .similarityFunction(SimilarityFunction.COSINE)
                .hnswParams(new HnswParams(16, 128, 64))
                .build();

        float[][] train = Arrays.copyOf(vectors, Math.min(N, 500));
        index.train(train);

        for (int i = 0; i < N; i++) {
            index.add("doc-" + i, i, vectors[i]);
        }

        // Queries
        int nQ = 20;
        Random qrng = new Random(999L);
        float[][] queries = new float[nQ][];
        for (int q = 0; q < nQ; q++) queries[q] = gaussianUnit(qrng, dims);

        // Brute-force ground truth (L2²)
        int totalHits = 0;
        int totalExpected = 0;

        for (int q = 0; q < nQ; q++) {
            // Ground truth: sort all vectors by L2² to query
            float[] dists = new float[N];
            for (int i = 0; i < N; i++) {
                float sum = 0;
                for (int d = 0; d < dims; d++) {
                    float diff = queries[q][d] - vectors[i][d];
                    sum += diff * diff;
                }
                dists[i] = sum;
            }
            Integer[] sorted = new Integer[N];
            for (int i = 0; i < N; i++) sorted[i] = i;
            Arrays.sort(sorted, (a, b) -> Float.compare(dists[a], dists[b]));

            Set<Integer> truthSet = new HashSet<>();
            for (int i = 0; i < k; i++) truthSet.add(sorted[i]);

            // SpectorIndex result
            ScoredResult[] results = index.search(queries[q], k);

            Set<Integer> resultSet = new HashSet<>();
            for (ScoredResult r : results) resultSet.add(r.index());

            int hits = 0;
            for (int idx : truthSet) {
                if (resultSet.contains(idx)) hits++;
            }
            totalHits += hits;
            totalExpected += k;

            if (hits < k) {
                System.out.printf("Query %d: recall=%d/%d%n", q, hits, k);
                System.out.printf("  Truth:  %s%n", truthSet);
                System.out.printf("  Got:    %s%n", resultSet);

                // Find which truth IDs are missing and why
                for (int truthIdx : truthSet) {
                    if (!resultSet.contains(truthIdx)) {
                        float truthDist = dists[truthIdx];
                        // Check what SpectorIndex scored this vector
                        System.out.printf("  MISS doc-%d: bruteL2²=%.8f, bruteL2=%.8f%n",
                                truthIdx, truthDist, Math.sqrt(truthDist));
                    }
                }

                // Print SpectorIndex's result scores
                System.out.printf("  SpectorIndex top-%d scores: ", k);
                for (ScoredResult r : results) {
                    System.out.printf("doc-%d(%.6f) ", r.index(), r.score());
                }
                System.out.println();

                // Print worst accepted vs best rejected
                float worstAccepted = results[results.length - 1].score();
                System.out.printf("  Worst accepted L2=%.8f%n", worstAccepted);
            }
        }

        double recall = (double) totalHits / totalExpected;
        System.out.printf("%nOverall recall@%d = %.4f (%d/%d)%n", k, recall, totalHits, totalExpected);
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
