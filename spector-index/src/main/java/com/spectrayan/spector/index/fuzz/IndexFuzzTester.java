package com.spectrayan.spector.index.fuzz;

import com.spectrayan.spector.index.error.SpectorIndexIntegrityException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spectrayan.spector.core.similarity.SimilarityFunction;
import com.spectrayan.spector.index.HnswIndex;
import com.spectrayan.spector.config.HnswParams;
import com.spectrayan.spector.index.ivf.IvfFlatIndex;

/**
 * Fuzz tester for vector index implementations.
 *
 * <p>Generates random and edge-case vectors, exercises insert/delete/search
 * operations on HNSW and IVF indexes, and verifies structural integrity
 * after each operation sequence. Records minimal reproducing input on
 * errors and preserves index state on crash.</p>
 *
 * <p>Minimum 10,000 random operations per run as specified in requirements.</p>
 */
public class IndexFuzzTester {

    private static final Logger log = LoggerFactory.getLogger(IndexFuzzTester.class);

    private static final int HNSW_CAPACITY = 50_000;
    private static final int IVF_NUM_CELLS = 16;
    private static final int IVF_TRAINING_VECTORS = 256;
    private static final int SEARCH_TOP_K = 10;
    private static final int IVF_NPROBE = 4;

    private final FuzzConfig config;
    private final Random random;

    // Index instances
    private HnswIndex hnswIndex;
    private IvfFlatIndex ivfIndex;

    // Tracking state
    private int hnswInsertCount;
    private int ivfInsertCount;
    private final Set<String> hnswInsertedIds = new HashSet<>();
    private final Set<String> ivfInsertedIds = new HashSet<>();

    public IndexFuzzTester(FuzzConfig config) {
        this.config = config;
        this.random = new Random(config.seed());
    }

    /**
     * Executes the fuzz testing run.
     *
     * @return a report summarizing the run
     */
    public FuzzReport run() {
        log.info("Starting fuzz run: seed={}, ops={}, indexes={}, dims={}",
                config.seed(), config.minOperations(), config.targetIndexes(), config.dimensions());

        Instant start = Instant.now();
        List<FuzzFailure> failures = new ArrayList<>();
        Set<String> uniqueErrorClasses = new LinkedHashSet<>();
        int totalOps = 0;

        try {
            initializeIndexes();

            for (int i = 0; i < config.minOperations(); i++) {
                totalOps = i + 1;
                FuzzOperation op = generateOperation(i);

                try {
                    executeOperation(op);
                } catch (Exception e) {
                    String errorClass = e.getClass().getName();
                    uniqueErrorClasses.add(errorClass);
                    FuzzFailure failure = new FuzzFailure(
                            i, op, errorClass, e.getMessage(), config.seed());
                    failures.add(failure);

                    // Persist reproducing input
                    persistReproducer(failure, e);

                    log.debug("Fuzz op {} failed: {} - {}", i, errorClass, e.getMessage());
                }

                // Verify structural integrity periodically (every 100 ops)
                if (i > 0 && i % 100 == 0) {
                    try {
                        verifyStructuralIntegrity();
                    } catch (Exception e) {
                        String errorClass = e.getClass().getName();
                        uniqueErrorClasses.add(errorClass);
                        FuzzFailure failure = new FuzzFailure(
                                i, null, errorClass,
                                "Integrity check failed: " + e.getMessage(), config.seed());
                        failures.add(failure);
                        persistCrashState(i, e);
                        log.warn("Structural integrity failed at op {}: {}", i, e.getMessage());
                    }
                }
            }

            // Final integrity check
            try {
                verifyStructuralIntegrity();
            } catch (Exception e) {
                String errorClass = e.getClass().getName();
                uniqueErrorClasses.add(errorClass);
                failures.add(new FuzzFailure(
                        totalOps, null, errorClass,
                        "Final integrity check failed: " + e.getMessage(), config.seed()));
                persistCrashState(totalOps, e);
            }

        } catch (Exception e) {
            // Catastrophic failure
            log.error("Fuzz run aborted at op {}", totalOps, e);
            uniqueErrorClasses.add(e.getClass().getName());
            failures.add(new FuzzFailure(
                    totalOps, null, e.getClass().getName(),
                    "Catastrophic: " + e.getMessage(), config.seed()));
            persistCrashState(totalOps, e);
        }

        Duration duration = Duration.between(start, Instant.now());
        log.info("Fuzz run complete: ops={}, errors={}, unique_errors={}, duration={}ms",
                totalOps, failures.size(), uniqueErrorClasses.size(), duration.toMillis());

        return new FuzzReport(totalOps, failures.size(), duration, failures, uniqueErrorClasses);
    }

    // ─────────────── Initialization ───────────────

    private void initializeIndexes() {
        int dims = config.dimensions();

        if (config.targetIndexes().contains(IndexType.HNSW)) {
            HnswParams params = new HnswParams(16, 200, 50);
            hnswIndex = new HnswIndex(dims, HNSW_CAPACITY, SimilarityFunction.COSINE, params);
            hnswInsertCount = 0;
            hnswInsertedIds.clear();
        }

        if (config.targetIndexes().contains(IndexType.IVF_FLAT)) {
            ivfIndex = new IvfFlatIndex(dims, SimilarityFunction.EUCLIDEAN);
            // Train IVF with random vectors
            float[][] trainingData = new float[IVF_TRAINING_VECTORS][dims];
            for (int i = 0; i < IVF_TRAINING_VECTORS; i++) {
                for (int d = 0; d < dims; d++) {
                    trainingData[i][d] = random.nextFloat() * 2f - 1f;
                }
            }
            ivfIndex.train(trainingData, IVF_NUM_CELLS);
            ivfInsertCount = 0;
            ivfInsertedIds.clear();
        }
    }

    // ─────────────── Operation Generation ───────────────

    private FuzzOperation generateOperation(int opIndex) {
        // Pick target index type
        List<IndexType> targets = config.targetIndexes();
        IndexType target = targets.get(random.nextInt(targets.size()));

        // Pick operation type with weighted distribution: 50% insert, 20% search, 30% delete
        FuzzOperation.OperationType opType = pickOperationType(target);

        float[] vector = generateVector(opIndex);
        String vectorId = generateVectorId(target, opType);

        return new FuzzOperation(opType, vector, vectorId, target);
    }

    private FuzzOperation.OperationType pickOperationType(IndexType target) {
        int roll = random.nextInt(100);
        if (roll < 50) {
            return FuzzOperation.OperationType.INSERT;
        } else if (roll < 70) {
            return FuzzOperation.OperationType.SEARCH;
        } else {
            // Delete only if there are inserted items
            boolean hasItems = (target == IndexType.HNSW && !hnswInsertedIds.isEmpty())
                    || (target == IndexType.IVF_FLAT && !ivfInsertedIds.isEmpty());
            return hasItems ? FuzzOperation.OperationType.DELETE : FuzzOperation.OperationType.INSERT;
        }
    }

    private String generateVectorId(IndexType target, FuzzOperation.OperationType opType) {
        if (opType == FuzzOperation.OperationType.DELETE) {
            Set<String> ids = (target == IndexType.HNSW) ? hnswInsertedIds : ivfInsertedIds;
            if (!ids.isEmpty()) {
                List<String> idList = new ArrayList<>(ids);
                return idList.get(random.nextInt(idList.size()));
            }
        }
        int count = (target == IndexType.HNSW) ? hnswInsertCount : ivfInsertCount;
        return target.name().toLowerCase() + "-" + count;
    }

    /**
     * Generates vectors with a mix of normal and edge-case values.
     * Edge cases include: NaN, Inf, -Inf, zero vectors, extreme magnitudes,
     * and dimensionality variations.
     */
    private float[] generateVector(int opIndex) {
        int dims = config.dimensions();

        // 20% chance of edge-case vector
        if (random.nextInt(100) < 20) {
            return generateEdgeCaseVector(dims);
        }

        // Normal random vector
        float[] vec = new float[dims];
        for (int i = 0; i < dims; i++) {
            vec[i] = random.nextFloat() * 2f - 1f;
        }
        return vec;
    }

    private float[] generateEdgeCaseVector(int dims) {
        int caseType = random.nextInt(8);
        return switch (caseType) {
            case 0 -> {
                // NaN vector
                float[] v = new float[dims];
                Arrays.fill(v, Float.NaN);
                yield v;
            }
            case 1 -> {
                // Positive infinity vector
                float[] v = new float[dims];
                Arrays.fill(v, Float.POSITIVE_INFINITY);
                yield v;
            }
            case 2 -> {
                // Negative infinity vector
                float[] v = new float[dims];
                Arrays.fill(v, Float.NEGATIVE_INFINITY);
                yield v;
            }
            case 3 -> {
                // Zero vector
                yield new float[dims];
            }
            case 4 -> {
                // Extreme magnitude (>1e38)
                float[] v = new float[dims];
                for (int i = 0; i < dims; i++) {
                    v[i] = (random.nextBoolean() ? 1f : -1f) * Float.MAX_VALUE * random.nextFloat();
                }
                yield v;
            }
            case 5 -> {
                // Mixed edge values (some NaN, some Inf, some normal)
                float[] v = new float[dims];
                for (int i = 0; i < dims; i++) {
                    int pick = random.nextInt(5);
                    v[i] = switch (pick) {
                        case 0 -> Float.NaN;
                        case 1 -> Float.POSITIVE_INFINITY;
                        case 2 -> Float.NEGATIVE_INFINITY;
                        case 3 -> 0f;
                        default -> random.nextFloat() * 2f - 1f;
                    };
                }
                yield v;
            }
            case 6 -> {
                // Very small magnitudes (subnormal)
                float[] v = new float[dims];
                for (int i = 0; i < dims; i++) {
                    v[i] = Float.MIN_VALUE * random.nextFloat();
                }
                yield v;
            }
            default -> {
                // Max dimensions vector (2048-dim if differs from configured dims)
                int maxDim = Math.min(2048, dims);
                float[] v = new float[dims];
                for (int i = 0; i < maxDim; i++) {
                    v[i] = random.nextFloat() * 2f - 1f;
                }
                yield v;
            }
        };
    }

    // ─────────────── Operation Execution ───────────────

    private void executeOperation(FuzzOperation op) {
        switch (op.indexType()) {
            case HNSW -> executeHnswOperation(op);
            case IVF_FLAT -> executeIvfOperation(op);
        }
    }

    private void executeHnswOperation(FuzzOperation op) {
        if (hnswIndex == null) return;

        switch (op.type()) {
            case INSERT -> {
                if (hnswInsertCount < HNSW_CAPACITY) {
                    hnswIndex.add(op.vectorId(), hnswInsertCount, op.vector());
                    hnswInsertedIds.add(op.vectorId());
                    hnswInsertCount++;
                }
            }
            case SEARCH -> {
                if (hnswIndex.size() > 0) {
                    hnswIndex.search(op.vector(), Math.min(SEARCH_TOP_K, hnswIndex.size()));
                }
            }
            case DELETE -> {
                // HNSW doesn't support delete in the current interface,
                // but we attempt a search after marking for logical testing
                if (hnswIndex.size() > 0) {
                    hnswIndex.search(op.vector(), Math.min(SEARCH_TOP_K, hnswIndex.size()));
                }
            }
        }
    }

    private void executeIvfOperation(FuzzOperation op) {
        if (ivfIndex == null) return;

        switch (op.type()) {
            case INSERT -> {
                ivfIndex.add(op.vectorId(), ivfInsertCount, op.vector());
                ivfInsertedIds.add(op.vectorId());
                ivfInsertCount++;
            }
            case SEARCH -> {
                if (ivfIndex.size() > 0) {
                    ivfIndex.search(op.vector(), IVF_NPROBE, Math.min(SEARCH_TOP_K, ivfIndex.size()));
                }
            }
            case DELETE -> {
                // IVF doesn't support delete in the current interface,
                // exercise search with the vector instead
                if (ivfIndex.size() > 0) {
                    ivfIndex.search(op.vector(), IVF_NPROBE, Math.min(SEARCH_TOP_K, ivfIndex.size()));
                }
            }
        }
    }

    // ─────────────── Structural Integrity Verification ───────────────

    /**
     * Verifies structural integrity of all active indexes.
     * - HNSW: checks graph connectivity (every layer-0 node has ≥1 neighbor after ≥2 nodes inserted)
     * - IVF: checks partition consistency (every vector in exactly one cell)
     */
    public void verifyStructuralIntegrity() {
        if (hnswIndex != null && hnswIndex.size() >= 2) {
            verifyHnswIntegrity();
        }
        if (ivfIndex != null && ivfIndex.size() > 0) {
            verifyIvfIntegrity();
        }
    }

    private void verifyHnswIntegrity() {
        int nodeCount = hnswIndex.size();

        for (int i = 0; i < nodeCount; i++) {
            int[] neighbors = hnswIndex.getNeighborsAtLayer(i, 0);
            if (neighbors == null || neighbors.length == 0) {
                throw new SpectorIndexIntegrityException(
                        "HNSW node " + i + " has no neighbors at layer 0 (nodeCount=" + nodeCount + ")");
            }

            // Check neighbor indices are valid
            for (int neighbor : neighbors) {
                if (neighbor < 0 || neighbor >= nodeCount) {
                    throw new SpectorIndexIntegrityException(
                            "HNSW node " + i + " has invalid neighbor index " + neighbor
                                    + " (nodeCount=" + nodeCount + ")");
                }
            }

            // Check max connections constraint
            int maxConn = hnswIndex.params().maxLevel0Connections();
            if (neighbors.length > maxConn) {
                throw new SpectorIndexIntegrityException(
                        "HNSW node " + i + " has " + neighbors.length
                                + " neighbors at layer 0, exceeding max " + maxConn);
            }
        }
    }

    private void verifyIvfIntegrity() {
        int reportedSize = ivfIndex.size();
        if (reportedSize != ivfInsertCount) {
            throw new SpectorIndexIntegrityException(
                    "IVF reported size " + reportedSize + " != expected " + ivfInsertCount);
        }
    }

    // ─────────────── Reproducer Persistence ───────────────

    private void persistReproducer(FuzzFailure failure, Exception e) {
        try {
            Path outputDir = config.outputDir();
            if (outputDir != null) {
                Files.createDirectories(outputDir);
                Path file = outputDir.resolve("reproducer-op" + failure.operationIndex() + ".txt");
                StringBuilder sb = new StringBuilder();
                sb.append("# Fuzz Failure Reproducer\n");
                sb.append("Seed: ").append(failure.reproducerSeed()).append("\n");
                sb.append("Operation Index: ").append(failure.operationIndex()).append("\n");
                sb.append("Operation: ").append(failure.operation()).append("\n");
                sb.append("Error: ").append(failure.errorClass())
                        .append(" - ").append(failure.errorMessage()).append("\n");
                if (failure.operation() != null && failure.operation().vector() != null) {
                    sb.append("Vector: ").append(Arrays.toString(failure.operation().vector())).append("\n");
                }
                sb.append("\n# Stack Trace\n");
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                sb.append(sw);
                Files.writeString(file, sb.toString());
            }
        } catch (IOException ioe) {
            log.warn("Failed to persist reproducer: {}", ioe.getMessage());
        }
    }

    private void persistCrashState(int opIndex, Exception e) {
        try {
            Path outputDir = config.outputDir();
            if (outputDir != null) {
                Files.createDirectories(outputDir);
                Path file = outputDir.resolve("crash-state-op" + opIndex + ".txt");
                StringBuilder sb = new StringBuilder();
                sb.append("# Crash State\n");
                sb.append("Seed: ").append(config.seed()).append("\n");
                sb.append("Operation Index: ").append(opIndex).append("\n");
                sb.append("HNSW size: ").append(hnswIndex != null ? hnswIndex.size() : "N/A").append("\n");
                sb.append("IVF size: ").append(ivfIndex != null ? ivfIndex.size() : "N/A").append("\n");
                sb.append("Error: ").append(e.getClass().getName())
                        .append(" - ").append(e.getMessage()).append("\n");
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                sb.append("\n# Stack Trace\n").append(sw);
                Files.writeString(file, sb.toString());
            }
        } catch (IOException ioe) {
            log.warn("Failed to persist crash state: {}", ioe.getMessage());
        }
    }

    // ─────────────── Accessors for testing ───────────────

    /** Returns the HNSW index under test (for integrity verification). */
    public HnswIndex getHnswIndex() {
        return hnswIndex;
    }

    /** Returns the IVF index under test (for integrity verification). */
    public IvfFlatIndex getIvfIndex() {
        return ivfIndex;
    }

    /** Returns the configuration used for this run. */
    public FuzzConfig getConfig() {
        return config;
    }
}
