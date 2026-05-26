package com.spectrayan.spector.bench;

import com.spectrayan.spector.core.quantization.QuantizationType;
import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.HnswParams;
import com.spectrayan.spector.index.QuantizedHnswIndex;
import com.spectrayan.spector.index.ScoredResult;

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
 * VASQ-8 vs VASQ-4 recall and latency benchmark using real Ollama embeddings.
 *
 * <p>Generates diverse sentences, embeds them via Ollama (qwen3-embedding, 4096-dim),
 * builds VASQ-8 and VASQ-4 HNSW indices, and measures:
 * <ul>
 *   <li><b>Recall@10</b> against exact brute-force ground truth</li>
 *   <li><b>Query latency</b> (avg, p50, p99, QPS)</li>
 *   <li><b>Memory usage</b> (bytes per vector for each quantization mode)</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn compile -pl spector-bench -q
 *   java --add-modules jdk.incubator.vector -Xmx8g \
 *        -cp spector-bench/target/classes:$(mvn -pl spector-bench dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout) \
 *        com.spectrayan.spector.bench.Vasq4RecallBench [size]
 * </pre>
 */
public class Vasq4RecallBench {

    // ── Configuration ───────────────────────────────────────────────────────
    private static int    DATASET_SIZE      = 5_000;
    private static final int    BATCH_SIZE          = 25;
    private static final int    CONCURRENT_BATCHES  = 2;
    private static final String MODEL               = "qwen3-embedding";
    private static final String OLLAMA_URL          = "http://localhost:11434/api/embed";
    private static final int    N_QUERIES           = 50;
    private static final int    WARMUP              = 50;
    private static final int    MEASURE             = 200;
    private static final int    K                   = 10;

    // ── Sentence templates ──────────────────────────────────────────────────
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
    };

    // ═══════════════════════════════════════════════════════════════════════
    //  Main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try { DATASET_SIZE = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { /* keep default */ }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║  VASQ-8 vs VASQ-4 Recall Benchmark  (%,d vectors, %s)  ║%n",
                DATASET_SIZE, MODEL);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Step 1: Obtain embeddings (cached) ──────────────────────────────
        Path cacheDir = Path.of("embedding-cache");
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve(MODEL + "-" + DATASET_SIZE + ".bin");

        float[][] embeddings;
        int dims;

        if (Files.exists(cacheFile)) {
            System.out.printf("📦 Loading cached embeddings from %s%n", cacheFile);
            embeddings = loadEmbeddings(cacheFile);
            dims = embeddings[0].length;
            System.out.printf("   Loaded %,d vectors × %d dims%n", embeddings.length, dims);
        } else {
            System.out.printf("🔄 Generating %,d sentences and embedding via Ollama (%s)...%n",
                    DATASET_SIZE, MODEL);
            String[] sentences = generateSentences(DATASET_SIZE);
            embeddings = embedAll(sentences);
            dims = embeddings[0].length;
            saveEmbeddings(cacheFile, embeddings);
            System.out.printf("   Embedded and cached %,d vectors × %d dims to %s%n",
                    embeddings.length, dims, cacheFile);
        }

        // Embed queries
        System.out.printf("🔍 Embedding %d query sentences...%n", N_QUERIES);
        String[] querySentences = generateQuerySentences(N_QUERIES);
        float[][] queries = embedAll(querySentences);
        System.out.printf("   Query dims: %d%n%n", queries[0].length);

        // Normalize for cosine
        for (float[] v : embeddings) normalize(v);
        for (float[] q : queries) normalize(q);

        // ── Step 2: Compute brute-force ground truth ────────────────────────
        System.out.println("📊 Computing brute-force ground truth (exact L2 on normalized)...");
        long gtStart = System.nanoTime();
        int[][] groundTruth = computeGroundTruth(embeddings, queries, K);
        System.out.printf("   Done in %dms%n%n", (System.nanoTime() - gtStart) / 1_000_000);

        // ── Step 3: Benchmark each configuration ────────────────────────────
        record Config(String label, QuantizationType qt, int oversampling) {}

        Config[] configs = {
            // No rescore
            new Config("VASQ-8 (no rescore)",       QuantizationType.VASQ,   1),
            new Config("VASQ-4 (no rescore)",       QuantizationType.VASQ_4, 1),
            // 2× oversampling rescore
            new Config("VASQ-8 (2× rescore)",       QuantizationType.VASQ,   2),
            new Config("VASQ-4 (2× rescore)",       QuantizationType.VASQ_4, 2),
            // 3× oversampling rescore (recommended)
            new Config("VASQ-8 (3× rescore)",       QuantizationType.VASQ,   3),
            new Config("VASQ-4 (3× rescore)",       QuantizationType.VASQ_4, 3),
            // 5× oversampling rescore
            new Config("VASQ-8 (5× rescore)",       QuantizationType.VASQ,   5),
            new Config("VASQ-4 (5× rescore)",       QuantizationType.VASQ_4, 5),
        };

        // Header
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-28s  %8s  %8s  %8s  %8s  %10s  %8s  %10s%n",
                "Configuration", "recall@" + K, "avg(ms)", "p50(ms)", "p99(ms)", "QPS", "bpv", "total(MB)");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");

        for (Config cfg : configs) {
            benchmarkConfig(cfg.label, cfg.qt, cfg.oversampling,
                    dims, embeddings, queries, groundTruth);
        }

        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%n✅ Benchmark complete. %,d vectors × %d dims, %d queries, model=%s%n",
                DATASET_SIZE, dims, N_QUERIES, MODEL);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Benchmark runner
    // ═══════════════════════════════════════════════════════════════════════

    private static void benchmarkConfig(String label, QuantizationType qt, int oversampling,
                                         int dims, float[][] embeddings,
                                         float[][] queries, int[][] groundTruth) {
        int n = embeddings.length;
        HnswParams hnswParams = new HnswParams(16, 128, 64);

        // Build index
        QuantizedHnswIndex index;
        if (qt == QuantizationType.VASQ) {
            index = QuantizedHnswIndex.vasq(dims, n, SimilarityFunction.COSINE, hnswParams, oversampling);
        } else {
            index = QuantizedHnswIndex.vasq4(dims, n, SimilarityFunction.COSINE, hnswParams, oversampling);
        }

        // Ingest
        for (int i = 0; i < n; i++) {
            index.add("doc-" + i, i, embeddings[i]);
        }

        int bpv = index.strategy() != null ? index.strategy().bytesPerVector() : -1;

        // Warmup
        for (int w = 0; w < WARMUP; w++) {
            index.search(queries[w % N_QUERIES], K);
        }

        // Measure
        long[] nanos = new long[MEASURE];
        ScoredResult[][] results = new ScoredResult[N_QUERIES][];
        for (int m = 0; m < MEASURE; m++) {
            int q = m % N_QUERIES;
            long start = System.nanoTime();
            results[q] = index.search(queries[q], K);
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

        double totalMB = bpv > 0 ? ((double) n * bpv) / (1024 * 1024) : -1;

        System.out.printf("%-28s  %8.4f  %8.3f  %8.3f  %8.3f  %10.0f  %8d  %10.2f%n",
                label, recall, avg, p50, p99, qps, bpv, totalMB);

        index.close();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Sentence generation
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    //  Ollama embedding
    // ═══════════════════════════════════════════════════════════════════════

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
        if (embStart < 0) throw new RuntimeException("No embeddings in response");

        int arrayStart = json.indexOf("[[", embStart);
        int arrayEnd = json.lastIndexOf("]]");
        if (arrayStart < 0 || arrayEnd < 0) throw new RuntimeException("Cannot parse embeddings");

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

    // ═══════════════════════════════════════════════════════════════════════
    //  Embedding cache (binary)
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    //  Math utilities
    // ═══════════════════════════════════════════════════════════════════════

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
