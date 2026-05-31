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

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Optimized large-scale real-embedding benchmark for SpectorIndex (50K–100K).
 *
 * <p>Features:
 * 1. Persistent cache directory at project root (survives mvn clean).
 * 2. Optimized batch size (500) to maximize GPU utilization in local Ollama.
 * 3. Scalable to 50,000 or 100,000 vectors.
 * 4. Measures recall@10 against exact brute-force L2 ground-truth.</p>
 *
 * <p>Run: {@code java --add-modules jdk.incubator.vector -Xmx12g -cp ... com.spectrayan.spector.bench.RealEmbeddingScaleBench [size]}</p>
 */
public class RealEmbeddingScaleBench {

    private static int DATASET_SIZE = 50_000; // default, can override via args
    private static final int BATCH_SIZE = 500; // Large batch size for high GPU throughput
    private static final int CONCURRENT_BATCHES = 4;
    private static final String MODEL = "qwen3-embedding";
    private static final String OLLAMA_URL = "http://localhost:11434/api/embed";
    private static final int N_QUERIES = 100;
    private static final int WARMUP = 100;
    private static final int MEASURE = 500;

    // Sentence templates for diverse text generation
    private static final String[][] TOPICS = {
        {"The study of %s reveals fundamental principles about %s in the natural world",
         "quantum mechanics", "particle physics", "thermodynamics", "electromagnetism",
         "molecular biology", "organic chemistry", "astrophysics", "genetics",
         "neuroscience", "biochemistry", "ecology", "paleontology"},
        {"Recent advances in %s have transformed how we approach %s in modern computing",
         "machine learning", "cloud computing", "cybersecurity", "blockchain",
         "quantum computing", "edge computing", "natural language processing", "robotics",
         "computer vision", "distributed systems", "microservices", "DevOps"},
        {"The %s period was marked by significant developments in %s across civilizations",
         "Renaissance", "Medieval", "Victorian", "Industrial Revolution",
         "Ancient Greek", "Roman Empire", "Ming Dynasty", "Ottoman",
         "Enlightenment", "Bronze Age", "Colonial", "Postwar"},
        {"The %s region is characterized by its unique %s and diverse ecosystems",
         "Amazon rainforest", "Saharan desert", "Arctic tundra", "Mediterranean coastal",
         "Himalayan mountain", "Pacific island", "African savanna", "European alpine",
         "Southeast Asian tropical", "North American prairie", "Australian outback", "Antarctic"},
        {"Clinical research on %s has led to breakthroughs in treating %s conditions",
         "immunotherapy", "gene therapy", "stem cells", "CRISPR editing",
         "mRNA vaccines", "monoclonal antibodies", "precision medicine", "regenerative medicine",
         "pharmacogenomics", "biomarkers", "clinical trials", "drug delivery"},
        {"The influence of %s on contemporary %s continues to shape creative expression",
         "impressionism", "surrealism", "minimalism", "abstract expressionism",
         "baroque music", "jazz improvisation", "digital art", "street photography",
         "postmodern literature", "experimental film", "modern dance", "installation art"},
        {"Global %s patterns indicate shifting trends in %s across major economies",
         "trade", "investment", "inflation", "employment",
         "monetary policy", "fiscal spending", "supply chain", "commodity pricing",
         "currency exchange", "interest rate", "GDP growth", "market volatility"},
        {"The impact of %s on %s requires urgent attention from policymakers worldwide",
         "deforestation", "ocean acidification", "carbon emissions", "plastic pollution",
         "biodiversity loss", "water scarcity", "soil degradation", "air quality",
         "glacier retreat", "coral bleaching", "species extinction", "urban sprawl"}
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try {
                DATASET_SIZE = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid size argument, using default: " + DATASET_SIZE);
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.printf("║ REAL EMBEDDING LARGE SCALE BENCHMARK (%,d vectors)  ║%n", DATASET_SIZE);
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        // Persistent cache directory at project root (independent of maven target)
        Path cacheDir = Path.of("embedding-cache");
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve(MODEL + "-" + DATASET_SIZE + ".bin");

        float[][] embeddings;
        int dims;

        if (Files.exists(cacheFile)) {
            System.out.printf("Loading cached embeddings from %s%n", cacheFile);
            embeddings = loadEmbeddings(cacheFile);
            dims = embeddings[0].length;
            System.out.printf("Loaded %,d vectors, %d dims%n", embeddings.length, dims);
        } else {
            System.out.printf("Generating %,d sentences...%n", DATASET_SIZE);
            String[] sentences = generateSentences(DATASET_SIZE);
            System.out.printf("Embedding via Ollama (%s, batch=%d, concurrent=%d)...%n",
                    MODEL, BATCH_SIZE, CONCURRENT_BATCHES);
            embeddings = embedAll(sentences);
            dims = embeddings[0].length;
            System.out.printf("Embedded %,d vectors, %d dims%n", embeddings.length, dims);
            saveEmbeddings(cacheFile, embeddings);
            System.out.printf("Cached to %s%n", cacheFile);
        }

        // Generate query embeddings (embed fresh sentences)
        System.out.printf("Embedding %d query sentences...%n", N_QUERIES);
        String[] querySentences = generateQuerySentences(N_QUERIES);
        float[][] queries = embedAll(querySentences);
        System.out.printf("Query dims: %d%n%n", queries[0].length);

        // Normalize all vectors for cosine comparability
        for (float[] v : embeddings) normalize(v);
        for (float[] q : queries) normalize(q);

        // Compute brute-force ground truth (L2 on normalized = equivalent to cosine rank)
        System.out.println("Computing brute-force ground truth...");
        long gtStart = System.nanoTime();
        int[][] groundTruth = computeGroundTruth(embeddings, queries, 10);
        System.out.printf("Ground truth computed in %dms%n%n", (System.nanoTime() - gtStart) / 1_000_000);

        // Test configurations
        int[] centroidCounts = {128, 256};
        int[] nProbes = {4, 8, 16, 32, 64};

        for (int nCentroids : centroidCounts) {
            System.out.printf("═══════════════════════════════════════════════════%n");
            System.out.printf("▶ nCentroids=%d, dataset=%,d × %d-dim%n", nCentroids, DATASET_SIZE, dims);

            for (int nProbe : nProbes) {
                if (nProbe > nCentroids) continue;

                SpectorIndex index = SpectorIndex.builder()
                        .dimensions(dims)
                        .nCentroids(nCentroids)
                        .nProbe(nProbe)
                        .shardThreshold(100_000) // Keep flat mode for direct comparisons
                        .oversamplingFactor(4)
                        .similarityFunction(SimilarityFunction.COSINE)
                        .hnswParams(new HnswParams(16, 128, 64))
                        .build();

                // Train on first 10,000 vectors
                int trainSize = Math.min(10_000, DATASET_SIZE);
                float[][] trainVecs = Arrays.copyOf(embeddings, trainSize);
                index.train(trainVecs);

                // Ingest
                long t0 = System.nanoTime();
                for (int i = 0; i < DATASET_SIZE; i++) {
                    index.add("doc-" + i, i, embeddings[i]);
                }
                long ingestMs = (System.nanoTime() - t0) / 1_000_000;

                // Warmup
                for (int w = 0; w < WARMUP; w++) {
                    index.search(queries[w % N_QUERIES], 10);
                }

                // Measure
                long[] nanos = new long[MEASURE];
                ScoredResult[][] results = new ScoredResult[N_QUERIES][];
                for (int m = 0; m < MEASURE; m++) {
                    int q = m % N_QUERIES;
                    long start = System.nanoTime();
                    results[q] = index.search(queries[q], 10);
                    nanos[m] = System.nanoTime() - start;
                }

                // Recall
                double recall = computeRecall(results, groundTruth, N_QUERIES);

                // Latency stats
                Arrays.sort(nanos);
                double avg = Arrays.stream(nanos).average().orElse(0) / 1e6;
                double p50 = nanos[MEASURE / 2] / 1e6;
                double p99 = nanos[(int) (MEASURE * 0.99)] / 1e6;
                double qps = 1e9 / (Arrays.stream(nanos).average().orElse(1));

                System.out.printf("  nProbe=%-3d  avg=%.3fms  p50=%.3fms  p99=%.3fms  QPS=%-6.0f  recall@10=%.4f  ingest=%dms%n",
                        nProbe, avg, p50, p99, qps, recall, ingestMs);

                index.close();
            }
        }

        System.out.println("═══════════════════════════════════════════════════");
    }

    // ── Sentence Generation ──

    private static String[] generateSentences(int count) {
        Random rng = new Random(42L);
        String[] sentences = new String[count];
        for (int i = 0; i < count; i++) {
            String[] topic = TOPICS[rng.nextInt(TOPICS.length)];
            String template = topic[0];
            String arg1 = topic[1 + rng.nextInt(topic.length - 1)];
            String arg2 = topic[1 + rng.nextInt(topic.length - 1)];
            sentences[i] = String.format(template, arg1, arg2) + " (variant " + i + ")";
        }
        return sentences;
    }

    private static String[] generateQuerySentences(int count) {
        Random rng = new Random(999L);
        String[] sentences = new String[count];
        for (int i = 0; i < count; i++) {
            String[] topic = TOPICS[rng.nextInt(TOPICS.length)];
            String template = topic[0];
            String arg1 = topic[1 + rng.nextInt(topic.length - 1)];
            String arg2 = topic[1 + rng.nextInt(topic.length - 1)];
            sentences[i] = String.format(template, arg1, arg2);
        }
        return sentences;
    }

    // ── Ollama Embedding ──

    private static float[][] embedAll(String[] sentences) throws Exception {
        int total = sentences.length;
        float[][] allEmbeddings = new float[total][];
        int dims = -1;

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_BATCHES);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

        List<Future<float[][]>> futures = new ArrayList<>();
        int batchCount = 0;

        for (int start = 0; start < total; start += BATCH_SIZE) {
            final int batchStart = start;
            final int batchEnd = Math.min(start + BATCH_SIZE, total);
            final int batchNum = ++batchCount;
            final int totalBatches = (total + BATCH_SIZE - 1) / BATCH_SIZE;

            futures.add(pool.submit(() -> {
                String[] batch = Arrays.copyOfRange(sentences, batchStart, batchEnd);
                float[][] result = embedBatch(client, batch);
                System.out.printf("  Batch %d/%d embedded (%d vectors)%n", batchNum, totalBatches, result.length);
                return result;
            }));
        }

        int idx = 0;
        for (int start = 0; start < total; start += BATCH_SIZE) {
            int batchEnd = Math.min(start + BATCH_SIZE, total);
            float[][] batchResult = futures.get(idx++).get();
            if (dims < 0) dims = batchResult[0].length;
            System.arraycopy(batchResult, 0, allEmbeddings, start, batchEnd - start);
        }

        pool.shutdown();
        return allEmbeddings;
    }

    private static float[][] embedBatch(HttpClient client, String[] texts) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(MODEL).append("\",\"input\":[");
        for (int i = 0; i < texts.length; i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(texts[i])).append("\"");
        }
        json.append("]}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama error " + response.statusCode() + ": " + response.body());
        }

        return parseEmbeddings(response.body());
    }

    private static float[][] parseEmbeddings(String json) {
        int embStart = json.indexOf("\"embeddings\"");
        if (embStart < 0) throw new RuntimeException("No embeddings in response: " + json.substring(0, Math.min(200, json.length())));

        int arrayStart = json.indexOf("[[", embStart);
        int arrayEnd = json.lastIndexOf("]]");
        if (arrayStart < 0 || arrayEnd < 0) throw new RuntimeException("Cannot parse embeddings array");

        String inner = json.substring(arrayStart + 1, arrayEnd + 1);
        List<float[]> vectors = new ArrayList<>();

        int pos = 0;
        while (pos < inner.length()) {
            int vecStart = inner.indexOf('[', pos);
            if (vecStart < 0) break;
            int vecEnd = inner.indexOf(']', vecStart);
            if (vecEnd < 0) break;

            String vecStr = inner.substring(vecStart + 1, vecEnd);
            String[] parts = vecStr.split(",");
            float[] vec = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vec[i] = Float.parseFloat(parts[i].trim());
            }
            vectors.add(vec);
            pos = vecEnd + 1;
        }

        return vectors.toArray(new float[0][]);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Embedding Cache ──

    private static void saveEmbeddings(Path path, float[][] embeddings) throws IOException {
        int n = embeddings.length;
        int dims = embeddings[0].length;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path.toFile())))) {
            out.writeInt(n);
            out.writeInt(dims);
            for (float[] vec : embeddings) {
                for (float v : vec) out.writeFloat(v);
            }
        }
    }

    private static float[][] loadEmbeddings(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path.toFile())))) {
            int n = in.readInt();
            int dims = in.readInt();
            float[][] embeddings = new float[n][dims];
            for (int i = 0; i < n; i++) {
                for (int d = 0; d < dims; d++) {
                    embeddings[i][d] = in.readFloat();
                }
            }
            return embeddings;
        }
    }

    // ── Math ──

    private static void normalize(float[] v) {
        double norm = 0;
        for (float f : v) norm += (double) f * f;
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) v[i] *= scale;
    }

    private static int[][] computeGroundTruth(float[][] data, float[][] queries, int k) {
        int[][] truth = new int[queries.length][k];
        for (int q = 0; q < queries.length; q++) {
            float[] dists = new float[data.length];
            for (int i = 0; i < data.length; i++) {
                float sum = 0;
                for (int d = 0; d < data[i].length; d++) {
                    float diff = queries[q][d] - data[i][d];
                    sum += diff * diff;
                }
                dists[i] = sum;
            }
            Integer[] indices = new Integer[data.length];
            for (int i = 0; i < data.length; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> Float.compare(dists[a], dists[b]));
            for (int i = 0; i < k; i++) truth[q][i] = indices[i];
        }
        return truth;
    }

    private static double computeRecall(ScoredResult[][] results, int[][] groundTruth, int nQueries) {
        int hits = 0, total = 0;
        for (int q = 0; q < nQueries; q++) {
            if (results[q] == null) continue;
            var truthSet = new HashSet<Integer>();
            for (int idx : groundTruth[q]) truthSet.add(idx);
            for (ScoredResult r : results[q]) {
                if (truthSet.contains(r.index())) hits++;
            }
            total += groundTruth[q].length;
        }
        return total > 0 ? (double) hits / total : 0;
    }
}
