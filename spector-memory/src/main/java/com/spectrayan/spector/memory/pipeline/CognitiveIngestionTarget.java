package com.spectrayan.spector.memory.pipeline;

import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.index.VectorIndex;
import com.spectrayan.spector.ingestion.IngestionTarget;
import com.spectrayan.spector.memory.MemoryType;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.cortex.WorkingMemoryStore;
import com.spectrayan.spector.memory.dopamine.FlashbulbPolicy;
import com.spectrayan.spector.memory.dopamine.SurpriseDetector;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.index.MemoryIndex.MemoryLocation;
import com.spectrayan.spector.memory.neurodivergent.IcnuWeights;
import com.spectrayan.spector.memory.neurodivergent.IngestionHints;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;
import com.spectrayan.spector.storage.VectorStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cognitive memory implementation of {@link IngestionTarget}.
 *
 * <p>Receives pre-embedded chunks from the unified {@link com.spectrayan.spector.ingestion.IngestionPipeline}
 * and performs the cognitive processing pipeline (steps 2–9):</p>
 *
 * <pre>
 *   Step  1b: Auto-extract synaptic tags (via pluggable {@link TagExtractor})
 *   Step  2: Encode synaptic tags → 64-bit Bloom filter
 *   Step  3: Compute surprise → auto-set importance (Dopamine engine)
 *   Step 3b: ICNU fusion — blend LLM hints (I/C/U) with native novelty (N)
 *   Step  4: Flashbulb check — extreme surprise gets full fidelity
 *   Step  5: Quantize vector to INT8 via calibrated ScalarQuantizer
 *   Step  6: Build cognitive header
 *   Step  7: Route to tier store and write
 *   Step 7b: Add to HNSW index (SEMANTIC type only)
 *   Step  8: Register in ID index
 *   Step  9: WAL append
 * </pre>
 *
 * <h3>Two Entry Points</h3>
 * <ul>
 *   <li>{@link #ingest(String, String, float[])} — from unified pipeline (bulk, auto-extracts tags)</li>
 *   <li>{@link #ingestCognitive} — from {@code SpectorMemory.remember()} (full cognitive params)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Stateless except for the subsystems it references (all thread-safe).
 * Multiple Virtual Threads can call {@link #ingest} concurrently.</p>
 */
public final class CognitiveIngestionTarget implements IngestionTarget {

    private static final Logger log = LoggerFactory.getLogger(CognitiveIngestionTarget.class);

    private final ScalarQuantizer quantizer;
    private final SurpriseDetector surpriseDetector;
    private final FlashbulbPolicy flashbulbPolicy;
    private final TierRouter tierRouter;
    private final MemoryIndex index;
    private final MemoryWal wal;
    private final WorkingMemoryStore workingStore;  // nullable
    private final IcnuWeights icnuWeights;
    private final VectorIndex semanticIndex;  // nullable — shared HNSW for semantic recall
    private final VectorStore vectorStore;    // nullable — engine's off-heap vector storage
    private final TagExtractor tagExtractor;
    private final boolean normalizeAtIngest;

    public CognitiveIngestionTarget(ScalarQuantizer quantizer,
                                     SurpriseDetector surpriseDetector,
                                     FlashbulbPolicy flashbulbPolicy,
                                     TierRouter tierRouter,
                                     MemoryIndex index,
                                     MemoryWal wal,
                                     WorkingMemoryStore workingStore,
                                     IcnuWeights icnuWeights,
                                     VectorIndex semanticIndex,
                                     VectorStore vectorStore,
                                     TagExtractor tagExtractor,
                                     boolean normalizeAtIngest) {
        this.quantizer = quantizer;
        this.surpriseDetector = surpriseDetector;
        this.flashbulbPolicy = flashbulbPolicy;
        this.tierRouter = tierRouter;
        this.index = index;
        this.wal = wal;
        this.workingStore = workingStore;
        this.icnuWeights = icnuWeights != null ? icnuWeights : IcnuWeights.DEFAULT;
        this.semanticIndex = semanticIndex;
        this.vectorStore = vectorStore;
        this.tagExtractor = tagExtractor != null ? tagExtractor : new ContentTagExtractor();
        this.normalizeAtIngest = normalizeAtIngest;
    }

    /**
     * Legacy constructor — defaults normalizeAtIngest to {@code true}.
     */
    public CognitiveIngestionTarget(ScalarQuantizer quantizer,
                                     SurpriseDetector surpriseDetector,
                                     FlashbulbPolicy flashbulbPolicy,
                                     TierRouter tierRouter,
                                     MemoryIndex index,
                                     MemoryWal wal,
                                     WorkingMemoryStore workingStore,
                                     IcnuWeights icnuWeights,
                                     VectorIndex semanticIndex,
                                     VectorStore vectorStore,
                                     TagExtractor tagExtractor) {
        this(quantizer, surpriseDetector, flashbulbPolicy, tierRouter,
                index, wal, workingStore, icnuWeights, semanticIndex,
                vectorStore, tagExtractor, true);
    }

    // ═══════════════════════════════════════════════════════════════
    // IngestionTarget — from unified pipeline (bulk ingestion)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ingests a pre-embedded chunk using SEMANTIC defaults.
     *
     * <p>Called by the unified IngestionPipeline during bulk file ingestion.
     * Auto-extracts synaptic tags via the configured {@link TagExtractor},
     * uses {@code MemoryType.SEMANTIC} and {@code MemorySource.OBSERVED}.</p>
     */
    @Override
    public void ingest(String id, String text, float[] vector) {
        // Step 1b: Auto-extract synaptic tags from document ID and content
        String[] tags = tagExtractor.extract(id, text);
        ingestCognitive(id, text, vector, MemoryType.SEMANTIC,
                tags, MemorySource.OBSERVED, null);
    }

    // ═══════════════════════════════════════════════════════════════
    // Full cognitive entry point — from SpectorMemory.remember()
    // ═══════════════════════════════════════════════════════════════

    /**
     * Full cognitive ingestion with all parameters.
     *
     * <p>Called by {@code SpectorMemory.remember()} with type, tags, source,
     * and optional ICNU hints from LLM assessment.</p>
     *
     * @param id     unique memory identifier
     * @param text   the memory content
     * @param vector pre-computed embedding vector
     * @param type   cognitive memory tier
     * @param tags   synaptic tag strings
     * @param source provenance source
     * @param hints  optional LLM-provided ICNU hints (null = novelty-only)
     */
    public void ingestCognitive(String id, String text, float[] vector,
                                 MemoryType type, String[] tags,
                                 MemorySource source, IngestionHints hints) {
        // Step 2: Encode synaptic tags
        long synapticTags = SynapticTagEncoder.encode(tags);

        // Step 1c: L2-normalize vector (required for Parabolic RBF lateral scoring)
        if (normalizeAtIngest) {
            vector = l2Normalize(vector);
        }

        // Step 5 (early): Quantize vector to INT8 — needed for WM distance scan
        byte[] quantized = quantizer.encode(vector);

        // Step 3: Compute surprise → auto-set importance (Dopamine engine)
        float nearestDist;
        if (workingStore != null && workingStore.count() > 0) {
            nearestDist = workingStore.nearestDistance(
                    vector, quantizer.mins(), quantizer.scales());
        } else {
            nearestDist = computeL2Norm(vector);
        }

        float importance;
        // Step 3b: ICNU fusion — blend LLM hints with native novelty
        if (hints != null && !hints.isEmpty()) {
            float rawNoveltyImportance = surpriseDetector.computeImportance(nearestDist);
            float noveltyNorm = Math.clamp(rawNoveltyImportance / 10.0f, 0f, 1f);
            importance = icnuWeights.fuse(hints, noveltyNorm);

            // Gaming detection logging
            if (hints.interest() == 1.0f && hints.challenge() == 1.0f
                    && hints.urgency() == 1.0f) {
                log.warn("ICNU anomaly: all-max hints for '{}' (I=1.0, C=1.0, U=1.0) — possible gaming", id);
            }

            log.debug("ICNU: id={}, I={}, C={}, N={}, U={}, fused={}",
                    id, hints.interest(), hints.challenge(), noveltyNorm,
                    hints.urgency(), importance);
        } else {
            importance = surpriseDetector.computeImportance(nearestDist);
        }

        // Step 4: Flashbulb check — extreme surprise gets full fidelity
        double zScore = surpriseDetector.stats().zScore(nearestDist);
        var flashbulb = flashbulbPolicy.evaluate(zScore);
        byte flags = SynapticHeaderConstants.withMemoryType((byte) 0, type.ordinal());
        if (flashbulb.isFlashbulb()) {
            importance = flashbulb.importance();
            flags = (byte) (flags | SynapticHeaderConstants.FLAG_PINNED);
        }

        // Step 6: Build cognitive header (with emotional context from hints)
        float l2Norm = computeL2Norm(vector);
        byte valence = (hints != null) ? hints.valence() : (byte) 0;
        byte arousal = (hints != null) ? hints.effectiveArousal() : (byte) 0;
        CognitiveHeader header = new CognitiveHeader(
                System.currentTimeMillis(), synapticTags, l2Norm, importance,
                0, (short) 0, valence, flags, arousal, 1.0f);

        // Step 7: Route to tier store and write
        long offset = tierRouter.write(type, header, quantized);

        // Step 7b: Add to shared HNSW index for semantic recall.
        // The HNSW is store-backed — must populate the engine's VectorStore first
        // so the HNSW can read vectors during graph construction and persistence.
        int storeIndex = -1;
        if (type == MemoryType.SEMANTIC && semanticIndex != null
                && !semanticIndex.isReadOnly()) {
            // Put vector in engine's VectorStore (returns the store index)
            if (vectorStore != null) {
                storeIndex = vectorStore.put(id, vector);
            } else {
                storeIndex = tierRouter.semantic().size() - 1;
            }
            semanticIndex.add(id, storeIndex, vector);
        }

        // Step 8: Register in ID index
        index.register(id, new MemoryLocation(type, offset, storeIndex), text, source, tags);

        // Step 9: WAL append
        wal.appendRemember(id, quantized);

        log.debug("Ingested '{}' as {} (importance={}, {} tags, hnswIdx={}, source={})",
                id, type, importance, tags.length, storeIndex, source);
    }

    // ═══════════════════════════════════════════════════════════════

    private static float computeL2Norm(float[] vector) {
        float sum = 0f;
        for (float v : vector) sum += v * v;
        return (float) Math.sqrt(sum);
    }

    /**
     * Returns a new L2-normalized copy of the vector.
     * Required for Parabolic RBF scoring to work correctly
     * (L2²=2.0 only equals orthogonality when ‖u‖ = ‖v‖ = 1).
     */
    private static float[] l2Normalize(float[] vector) {
        float norm = computeL2Norm(vector);
        if (norm == 0f || Math.abs(norm - 1.0f) < 1e-6f) return vector; // already normalized or zero
        float[] normalized = new float[vector.length];
        float invNorm = 1.0f / norm;
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] * invNorm;
        }
        return normalized;
    }
}
