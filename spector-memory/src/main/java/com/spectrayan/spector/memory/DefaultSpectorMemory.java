/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory;

import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.TextGenerationProvider;
import com.spectrayan.spector.memory.amygdala.ValenceTracker;
import com.spectrayan.spector.memory.cortex.CentroidRouter;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.ProceduralMemoryStore;
import com.spectrayan.spector.memory.cortex.SemanticMemoryStore;
import com.spectrayan.spector.memory.cortex.SemanticRecallStrategy;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.cortex.WorkingMemoryStore;
import com.spectrayan.spector.memory.dopamine.FlashbulbPolicy;
import com.spectrayan.spector.memory.dopamine.SurpriseDetector;
import com.spectrayan.spector.memory.graph.EntityExtractionMode;
import com.spectrayan.spector.memory.graph.EntityExtractor;
import com.spectrayan.spector.memory.graph.EntityGraph;
import com.spectrayan.spector.memory.graph.LlmEntityExtractor;
import com.spectrayan.spector.memory.graph.NoOpEntityExtractor;
import com.spectrayan.spector.memory.habituation.HabituationPenalty;
import com.spectrayan.spector.memory.hebbian.CoActivationTracker;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.hippocampus.CircadianPolicy;
import com.spectrayan.spector.memory.hippocampus.ReflectDaemon;
import com.spectrayan.spector.memory.index.MemoryIndex;
import com.spectrayan.spector.memory.index.MemoryIndex.MemoryLocation;
import com.spectrayan.spector.memory.inhibition.SuppressionSet;
import com.spectrayan.spector.memory.interference.SemanticDeduplicator;
import com.spectrayan.spector.memory.metamemory.MemoryInsight;
import com.spectrayan.spector.memory.metamemory.MemoryIntrospector;
import com.spectrayan.spector.memory.neurodivergent.IcnuWeights;
import com.spectrayan.spector.memory.neurodivergent.LateralEvaluator;
import com.spectrayan.spector.memory.pipeline.HebbianCoActivationListener;
import com.spectrayan.spector.memory.pipeline.CognitiveIngestionTarget;
import com.spectrayan.spector.memory.pipeline.LtpReconsolidationListener;
import com.spectrayan.spector.memory.pipeline.RecallPipeline;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.prospective.Reminder;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.sync.WalEvent;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.temporal.TemporalChain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Default implementation of {@link SpectorMemory} — the Zero-GC Cognitive Backbone for Autonomous Agents.
 *
 * <h3>Design Pattern: Façade</h3>
 * <p>{@code DefaultSpectorMemory} is a thin façade that composes 5 subsystems:</p>
 * <ul>
 *   <li>{@link com.spectrayan.spector.ingestion.IngestionPipeline} — 10-step ingest (embed → quantize → route → WAL)</li>
 *   <li>{@link RecallPipeline} — 8-step recall (embed → score → filter → sort)</li>
 *   <li>{@link TierRouter} — tier store registry (Working, Episodic, Semantic, Procedural)</li>
 *   <li>{@link MemoryIndex} — ID → metadata index (locations, text, tags, sources)</li>
 *   <li>{@link ReflectDaemon} — sleep consolidation (REM cycle, tombstone compaction)</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 *   var memory = DefaultSpectorMemory.builder()
 *       .dimensions(768)
 *       .embeddingProvider(ollamaProvider)
 *       .persistence(Path.of("/data/agent-memory"))
 *       .build();
 *
 *   memory.remember("user-pref", "User prefers dark mode.",
 *       MemoryType.SEMANTIC, MemorySource.USER_STATED, "ui", "preferences").join();
 *
 *   List<CognitiveResult> results = memory.recall("what theme?",
 *       RecallOptions.builder().topK(5).synapticFilter("preferences").build());
 * }</pre>
 */
public final class DefaultSpectorMemory implements SpectorMemory {

    private static final Logger log = LoggerFactory.getLogger(DefaultSpectorMemory.class);

    // ── Core Subsystems (Façade composition) ──
    private final CognitiveIngestionTarget cognitiveTarget;
    private final EmbeddingProvider embeddingProvider;
    private final RecallPipeline recallPipeline;
    private final TierRouter tierRouter;
    private final MemoryIndex index;
    private final ScalarQuantizer quantizer;

    // ── Biological Subsystems ──
    private final ValenceTracker valenceTracker;
    private final ReflectDaemon reflectDaemon;
    private final CoActivationTracker coActivationTracker;
    private final SuppressionSet suppressionSet;
    private final HabituationPenalty habituationPenalty;
    private final ProspectiveScheduler prospectiveScheduler;
    private final MemoryIntrospector introspector;
    private final MemoryWal wal;
    private final LateralEvaluator lateralEvaluator;

    // ── 3-Layer Cognitive Graph ──
    private final HebbianGraph hebbianGraph;
    private final TemporalChain temporalChain;
    private final EntityGraph entityGraph;

    // ── Configuration ──
    private final int dimensions;
    private final MemoryPersistenceMode persistenceMode;
    private final Path persistencePath;
    private final CircadianPolicy circadianPolicy;
    private final CognitiveProfileConfig profileConfig;
    private final ExecutorService virtualExecutor;
    private final AtomicInteger episodicIngestCount = new AtomicInteger(0);

    private DefaultSpectorMemory(Builder builder) {
        this.dimensions = builder.dimensions;
        this.persistenceMode = builder.persistenceMode;
        this.persistencePath = builder.persistencePath;
        if (builder.embeddingProvider == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "embeddingProvider is required"); } EmbeddingProvider embeddingProvider = builder.embeddingProvider;
        this.circadianPolicy = builder.circadianPolicy;
        this.profileConfig = builder.profileConfig;
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

        boolean isDisk = persistenceMode == MemoryPersistenceMode.DISK;

        // Resolve persistence path for DISK mode
        Path basePath;
        if (isDisk && builder.persistencePath != null) {
            basePath = builder.persistencePath;
        } else if (isDisk) {
            basePath = Path.of(System.getProperty("java.io.tmpdir"),
                    "spector-memory-" + ProcessHandle.current().pid());
            log.warn("DISK persistence mode with no explicit path — using temp directory: {}", basePath);
        } else {
            basePath = null;
        }

        // ── Quantization calibration ──
        if (builder.quantizer != null) {
            this.quantizer = builder.quantizer;
        } else {
            float[] defaultMins = new float[dimensions];
            float[] defaultMaxs = new float[dimensions];
            java.util.Arrays.fill(defaultMins, -1.0f);
            java.util.Arrays.fill(defaultMaxs, 1.0f);
            this.quantizer = ScalarQuantizer.fromBounds(dimensions, defaultMins, defaultMaxs);
        }

        // ── Tier Stores → TierRouter ──
        int quantizedVecBytes = dimensions;

        // Working memory: configurable persistence (default: volatile)
        WorkingMemoryStore workingStore;
        if (isDisk && builder.persistWorkingMemory && basePath != null) {
            workingStore = new WorkingMemoryStore(quantizedVecBytes, builder.workingCapacity,
                    basePath.resolve("working.mem"));
        } else {
            workingStore = new WorkingMemoryStore(quantizedVecBytes, builder.workingCapacity);
        }

        // Episodic: always uses its own directory (already file-backed)
        Path episodicPath;
        if (basePath != null) {
            episodicPath = basePath.resolve("episodic");
        } else {
            episodicPath = Path.of(System.getProperty("java.io.tmpdir"),
                    "spector-memory-" + ProcessHandle.current().pid() + "-" + System.nanoTime(),
                    "episodic");
        }
        EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(
                episodicPath, quantizedVecBytes, builder.episodicPartitionCapacity);

        // Semantic: file-backed in DISK mode
        SemanticMemoryStore semanticStore;
        if (isDisk && basePath != null) {
            semanticStore = new SemanticMemoryStore(quantizedVecBytes, builder.semanticCapacity,
                    basePath.resolve("semantic.mem"));
        } else {
            semanticStore = new SemanticMemoryStore(quantizedVecBytes, builder.semanticCapacity);
        }

        // Procedural: file-backed in DISK mode
        ProceduralMemoryStore proceduralStore;
        if (isDisk && basePath != null) {
            proceduralStore = new ProceduralMemoryStore(quantizedVecBytes, builder.proceduralCapacity,
                    basePath.resolve("procedural.mem"));
        } else {
            proceduralStore = new ProceduralMemoryStore(quantizedVecBytes, builder.proceduralCapacity);
        }

        this.tierRouter = new TierRouter(workingStore, episodicStore, semanticStore, proceduralStore);

        // ── Memory Index (load from disk if DISK mode and file exists) ──
        if (isDisk && basePath != null) {
            this.index = MemoryIndex.load(basePath.resolve("memory-index.mem"));
        } else {
            this.index = new MemoryIndex();
        }

        // ── WAL (file-backed in DISK mode) ──
        if (isDisk && basePath != null) {
            this.wal = new MemoryWal(basePath.resolve("wal"));
        } else {
            this.wal = new MemoryWal();
        }

        // ── Biological Subsystems ──
        SurpriseDetector surpriseDetector = new SurpriseDetector(builder.surpriseWarmup);
        FlashbulbPolicy flashbulbPolicy = new FlashbulbPolicy(builder.flashbulbThreshold);
        this.valenceTracker = new ValenceTracker(builder.valenceLearningRate);
        // CoActivationTracker: load from disk if available, else create fresh
        if (isDisk && basePath != null) {
            this.coActivationTracker = CoActivationTracker.load(
                    basePath.resolve("coactivation.tracker"), 10_000, 20_000);
        } else {
            this.coActivationTracker = new CoActivationTracker();
        }
        this.suppressionSet = new SuppressionSet();
        this.habituationPenalty = new HabituationPenalty(0.2f, builder.inhibitionTtlMs, builder.inhibitionFloor);
        this.prospectiveScheduler = new ProspectiveScheduler();
        this.introspector = new MemoryIntrospector(coActivationTracker);
        this.lateralEvaluator = new LateralEvaluator();
        this.reflectDaemon = new ReflectDaemon(
                circadianPolicy,
                builder.dimensions > 0 ? new CentroidRouter(builder.dimensions) : null,
                builder.textGenerationProvider,
                embeddingProvider,
                5, // minClusterSize
                builder.pinSourceEpisodes,
                builder.pinnedQuota);

        // ── 3-Layer Cognitive Graph ──
        int graphCapacity = builder.hebbianGraphCapacity > 0
                ? builder.hebbianGraphCapacity : builder.episodicPartitionCapacity;

        // HebbianGraph: load from disk if available, else create fresh
        if (isDisk && basePath != null) {
            this.hebbianGraph = HebbianGraph.load(
                    basePath.resolve("hebbian.graph"), graphCapacity);
        } else {
            this.hebbianGraph = new HebbianGraph(graphCapacity);
        }

        // TemporalChain: load from disk if available, else create fresh
        int temporalCapacity = builder.temporalChainCapacity > 0
                ? builder.temporalChainCapacity : graphCapacity;
        if (isDisk && basePath != null) {
            this.temporalChain = TemporalChain.load(
                    basePath.resolve("temporal.chain"), temporalCapacity);
        } else {
            this.temporalChain = new TemporalChain(temporalCapacity);
        }

        // EntityGraph + EntityExtractor: based on mode
        EntityExtractor entityExtractor;
        if (builder.entityExtractionMode == EntityExtractionMode.LLM
                && builder.textGenerationProvider != null) {
            entityExtractor = new LlmEntityExtractor(
                    builder.textGenerationProvider,
                    builder.maxEntitiesPerMemory, builder.maxRelationsPerMemory);
        } else if (builder.entityExtractionMode == EntityExtractionMode.CUSTOM
                && builder.entityExtractor != null) {
            entityExtractor = builder.entityExtractor;
        } else {
            entityExtractor = NoOpEntityExtractor.INSTANCE;
        }

        boolean entityEnabled = builder.entityExtractionMode != EntityExtractionMode.NONE;
        if (entityEnabled) {
            int entityCap = builder.entityGraphCapacity;
            int edgeCap = entityCap * EntityGraph.MAX_DEGREE;
            if (isDisk && basePath != null) {
                this.entityGraph = EntityGraph.load(
                        basePath.resolve("entity.graph"), entityCap, edgeCap);
            } else {
                this.entityGraph = new EntityGraph(entityCap, edgeCap);
            }
        } else {
            this.entityGraph = null;
        }

        // ── Pipelines ──
        this.embeddingProvider = embeddingProvider;
        this.cognitiveTarget = new CognitiveIngestionTarget(
                quantizer, surpriseDetector, flashbulbPolicy,
                tierRouter, index, wal, workingStore, builder.icnuWeights,
                builder.semanticIndex, builder.vectorStore, builder.tagExtractor, true,
                hebbianGraph, temporalChain, entityExtractor, entityGraph);

        // Build optional fused semantic recall strategy
        SemanticRecallStrategy semanticStrategy = builder.semanticIndex != null
                ? new SemanticRecallStrategy(builder.semanticIndex, semanticStore, index)
                : null;

        this.recallPipeline = new RecallPipeline(
                embeddingProvider, tierRouter, index,
                suppressionSet, habituationPenalty, prospectiveScheduler, wal,
                quantizer.mins(), quantizer.scales(), semanticStrategy,
                null, hebbianGraph, temporalChain, entityGraph, entityExtractor);

        // Register post-recall observers (Phase 6: Observer pattern)
        recallPipeline.addListener(new LtpReconsolidationListener(index, tierRouter, wal));
        recallPipeline.addListener(new HebbianCoActivationListener(coActivationTracker));

        log.info("SpectorMemory initialized: dimensions={}, model={}, persistence={}, mode={}, quantizer={}",
                dimensions, embeddingProvider.modelName(),
                basePath != null ? basePath : "in-memory",
                persistenceMode,
                builder.quantizer != null ? "user-provided" : "identity-default");
    }

    // ══════════════════════════════════════════════════════════════
    // INGESTION TARGET — for unified IngestionPipeline
    // ══════════════════════════════════════════════════════════════

    @Override
    public CognitiveIngestionTarget target() {
        return cognitiveTarget;
    }

    // ══════════════════════════════════════════════════════════════
    // CORE API — remember / recall / forget / reflect
    // ══════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source, String... tags) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Embed text, then pass to cognitive target
                float[] vector = embeddingProvider.embed(text).vector();
                cognitiveTarget.ingestCognitive(id, text, vector, type, tags, source, null);

                // Circadian trigger: auto-reflect after volume threshold
                if (type == MemoryType.EPISODIC) {
                    int count = episodicIngestCount.incrementAndGet();
                    if (count >= circadianPolicy.volumeTrigger()) {
                        episodicIngestCount.set(0);
                        CompletableFuture.runAsync(() -> {
                            log.info("Circadian volume trigger: {} episodic memories → auto-reflect", count);
                            reflect();
                        }, virtualExecutor);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to remember '{}': {}", id, e.getMessage(), e);
                throw new SpectorServerException(ErrorCode.INTERNAL_ERROR, e, "Memory ingestion failed for id=" + id);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              String... tags) {
        return remember(id, text, type, MemorySource.OBSERVED, tags);
    }

    @Override
    public List<CognitiveResult> recall(String queryText, RecallOptions options) {
        return recallPipeline.recall(queryText, options);
    }

    @Override
    public List<CognitiveResult> recall(String queryText, CognitiveProfile profile) {
        CognitiveProfile effective = profileConfig.validate(profile);
        return recall(queryText, RecallOptions.builder().profile(effective).build());
    }

    @Override
    public List<CognitiveResult> recall(String queryText) {
        return recall(queryText, RecallOptions.DEFAULT);
    }

    @Override
    public void forget(String id) {
        if (id == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "id"); }
        MemoryLocation loc = index.locate(id);
        if (loc == null) {
            log.warn("Forget: memory '{}' not found in index", id);
            return;
        }

        MemorySegment segment = tierRouter.segmentFor(loc.type());
        if (segment != null) {
            CognitiveRecordLayout layout = tierRouter.layoutFor(loc.type());
            layout.tombstone(segment, loc.offset());
        }

        wal.appendForget(id);
        index.remove(id);
        log.debug("Forget: '{}' tombstoned", id);
    }

    @Override
    public ReflectReport reflect() {
        log.info("Manual reflection triggered");
        ReflectReport report = reflectDaemon.runCycle(
                tierRouter.episodic(), tierRouter.semantic(),
                offset -> index.findTextByOffset(MemoryType.EPISODIC, offset));

        // ── Graph Decay (Sleep Consolidation) ──
        // Hebbian edges decay by 10% per reflection cycle (biological synaptic homeostasis)
        int hebbianDecayed = hebbianGraph.decayEdges(0.9f);
        if (hebbianDecayed > 0) {
            log.info("Reflect: Hebbian graph decayed {} weak edges", hebbianDecayed);
        }

        // Temporal chain: decay old links (prune chains older than 7 days)
        // TemporalChain nodes don't have a decay mechanism yet — future work

        wal.append(WalEvent.EventType.REFLECT, "system", null);
        return report;
    }

    // ══════════════════════════════════════════════════════════════
    // EXTENDED API — reinforce / suppress / introspect / Hebbian
    // ══════════════════════════════════════════════════════════════

    @Override
    public void reinforce(String memoryId, byte valence) {
        if (memoryId == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "memoryId"); }
        MemoryLocation loc = index.locate(memoryId);
        if (loc == null) {
            log.warn("Reinforce: memory '{}' not found", memoryId);
            return;
        }

        MemorySegment segment = tierRouter.segmentFor(loc.type());
        if (segment != null) {
            CognitiveRecordLayout layout = tierRouter.layoutFor(loc.type());
            valenceTracker.reinforce(segment, loc.offset(), layout, valence);
            layout.incrementRecallCount(segment, loc.offset()); // LTP on explicit use
        }

        // Neurodivergent: Feed lateral evaluator based on whether this was a lateral result
        if (recallPipeline.wasLateral(memoryId)) {
            if (valence > 0) {
                lateralEvaluator.recordLateralReinforcement();
                log.debug("Lateral reinforcement: '{}' (positive valence={})", memoryId, valence);
            } else if (valence < 0) {
                lateralEvaluator.recordLateralSuppression();
                log.debug("Lateral suppression via reinforce: '{}' (negative valence={})", memoryId, valence);
            }
        }

        wal.appendReinforce(memoryId, valence);
        log.debug("Reinforce: '{}' with valence={}", memoryId, valence);
    }

    @Override
    public void suppress(String memoryId, String reason) {
        suppressionSet.suppress(memoryId, reason);
        // Also register offset for hot-loop filtering
        MemoryLocation loc = index.locate(memoryId);
        if (loc != null) {
            suppressionSet.registerOffset(loc.type().ordinal(), loc.offset());
        }

        // Neurodivergent: Feed lateral evaluator
        if (recallPipeline.wasLateral(memoryId)) {
            lateralEvaluator.recordLateralSuppression();
            log.debug("Lateral suppression: '{}' (reason={})", memoryId, reason);
        }
    }

    @Override
    public void suppress(String memoryId) { suppress(memoryId, null); }

    @Override
    public void unsuppress(String memoryId) { suppressionSet.unsuppress(memoryId); }

    @Override
    public void markResolved(String memoryId) {
        var loc = index.locate(memoryId);
        if (loc == null) return;
        tierRouter.layoutFor(loc.type()).markResolved(tierRouter.segmentFor(loc.type()), loc.offset());
        log.debug("Zeigarnik: marked '{}' as RESOLVED", memoryId);
    }

    @Override
    public void markUnresolved(String memoryId) {
        var loc = index.locate(memoryId);
        if (loc == null) return;
        tierRouter.layoutFor(loc.type()).markUnresolved(tierRouter.segmentFor(loc.type()), loc.offset());
        log.debug("Zeigarnik: marked '{}' as UNRESOLVED", memoryId);
    }

    @Override
    public MemoryInsight introspect(String topic) {
        List<CognitiveResult> results = recall(topic, RecallOptions.builder().topK(20).build());
        return introspector.analyze(topic, results);
    }

    // ══════════════════════════════════════════════════════════════
    // PROSPECTIVE / SCRATCHPAD / STATS
    // ══════════════════════════════════════════════════════════════

    @Override
    public Reminder scheduleReminder(String text, Instant triggerAt, String... tags) {
        return prospectiveScheduler.schedule(text, triggerAt, tags);
    }

    @Override
    public Reminder scheduleReminder(String text, Duration delay, String... tags) {
        return prospectiveScheduler.scheduleAfter(text, delay, tags);
    }

    @Override
    public CompletableFuture<Void> scratchpad(String text) {
        return remember("scratchpad-" + System.nanoTime(), text, MemoryType.WORKING);
    }

    @Override
    public int totalMemories() { return tierRouter.totalCount(); }

    @Override
    public int memoryCount(MemoryType type) { return tierRouter.countFor(type); }

    @Override
    public int decay(Duration olderThan, float factor) {
        if (olderThan == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "olderThan"); }
        if (factor < 0f || factor > 1f) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "factor", 0, 1, 0);

        long nowMs = System.currentTimeMillis();
        long thresholdMs = nowMs - olderThan.toMillis();

        var partitions = tierRouter.episodic().partitions();
        if (partitions.isEmpty()) return 0;

        // Parallel decay: each partition on its own Virtual Thread
        try {
            java.util.List<java.util.concurrent.Callable<Integer>> tasks = new java.util.ArrayList<>(partitions.size());
            for (var partition : partitions) {
                tasks.add(() -> {
                    int count = 0;
                    CognitiveRecordLayout layout = partition.layout();
                    MemorySegment segment = partition.segment();
                    for (int i = 0; i < partition.count(); i++) {
                        long offset = partition.recordOffset(i);
                        byte flags = layout.readFlags(segment, offset);
                        if (SynapticHeaderConstants.isTombstoned(flags)) continue;

                        long ts = layout.readTimestamp(segment, offset);
                        if (ts < thresholdMs) {
                            float oldImp = layout.readImportance(segment, offset);
                            layout.writeImportance(segment, offset, oldImp * factor);
                            count++;
                        }
                    }
                    return count;
                });
            }
            java.util.List<Integer> results = ConcurrentTasks.forkJoinAll(tasks);
            int affected = 0;
            for (int c : results) affected += c;
            log.info("Decay: {} memories older than {} multiplied by {}", affected, olderThan, factor);
            return affected;
        } catch (ConcurrentExecutionException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Parallel decay failed, falling back to sequential: {}", e.getMessage());
            // Sequential fallback
            int affected = 0;
            for (var partition : partitions) {
                CognitiveRecordLayout layout = partition.layout();
                MemorySegment segment = partition.segment();
                for (int i = 0; i < partition.count(); i++) {
                    long offset = partition.recordOffset(i);
                    byte flags = layout.readFlags(segment, offset);
                    if (SynapticHeaderConstants.isTombstoned(flags)) continue;
                    long ts = layout.readTimestamp(segment, offset);
                    if (ts < thresholdMs) {
                        float oldImp = layout.readImportance(segment, offset);
                        layout.writeImportance(segment, offset, oldImp * factor);
                        affected++;
                    }
                }
            }
            log.info("Decay: {} memories older than {} multiplied by {}", affected, olderThan, factor);
            return affected;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SUBSYSTEM ACCESSORS
    // ══════════════════════════════════════════════════════════════

    @Override public CoActivationTracker coActivation() { return coActivationTracker; }
    @Override public MemoryWal wal() { return wal; }
    @Override public ProspectiveScheduler prospective() { return prospectiveScheduler; }
    @Override public SuppressionSet suppression() { return suppressionSet; }
    @Override public HabituationPenalty habituation() { return habituationPenalty; }
    @Override public ScalarQuantizer quantizer() { return quantizer; }
    @Override public CognitiveIngestionTarget cognitiveTarget() { return cognitiveTarget; }
    @Override public RecallPipeline recallPipeline() { return recallPipeline; }
    @Override public TierRouter tierRouter() { return tierRouter; }
    @Override public MemoryIndex index() { return index; }
    @Override public LateralEvaluator lateralEvaluator() { return lateralEvaluator; }
    @Override public HebbianGraph hebbianGraph() { return hebbianGraph; }
    @Override public TemporalChain temporalChain() { return temporalChain; }
    @Override public EntityGraph entityGraph() { return entityGraph; }

    @Override
    public void close() {
        log.info("SpectorMemory closing ({} total memories, mode={})", totalMemories(), persistenceMode);

        // Save MemoryIndex to disk if DISK mode
        if (persistenceMode == MemoryPersistenceMode.DISK && persistencePath != null) {
            try {
                index.save(persistencePath.resolve("memory-index.mem"));
            } catch (Exception e) {
                log.error("Failed to save MemoryIndex on close: {}", e.getMessage(), e);
            }

            // Save 3-Layer Cognitive Graph
            try {
                hebbianGraph.save(persistencePath.resolve("hebbian.graph"));
            } catch (Exception e) {
                log.error("Failed to save HebbianGraph on close: {}", e.getMessage(), e);
            }
            try {
                temporalChain.save(persistencePath.resolve("temporal.chain"));
            } catch (Exception e) {
                log.error("Failed to save TemporalChain on close: {}", e.getMessage(), e);
            }
            if (entityGraph != null) {
                try {
                    entityGraph.save(persistencePath.resolve("entity.graph"));
                } catch (Exception e) {
                    log.error("Failed to save EntityGraph on close: {}", e.getMessage(), e);
                }
            }
            try {
                coActivationTracker.save(persistencePath.resolve("coactivation.tracker"));
            } catch (Exception e) {
                log.error("Failed to save CoActivationTracker on close: {}", e.getMessage(), e);
            }
        }

        virtualExecutor.close();
        tierRouter.close();
        wal.close();
        hebbianGraph.close();
        temporalChain.close();
        coActivationTracker.close();
        if (entityGraph != null) entityGraph.close();
    }

    // ══════════════════════════════════════════════════════════════
    // BUILDER
    // ══════════════════════════════════════════════════════════════

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int dimensions;
        private EmbeddingProvider embeddingProvider;
        private Path persistencePath;
        private MemoryPersistenceMode persistenceMode = MemoryPersistenceMode.DISK;
        private boolean persistWorkingMemory = false;
        private CircadianPolicy circadianPolicy = CircadianPolicy.DEFAULT;
        private int workingCapacity = 100;
        private int episodicPartitionCapacity = 10_000;
        private int semanticCapacity = 100_000;
        private int proceduralCapacity = 1_000;
        private int surpriseWarmup = 20;
        private double flashbulbThreshold = 3.0;
        private float valenceLearningRate = 0.3f;
        private float deduplicationRadius = 0.05f;
        private TextGenerationProvider textGenerationProvider;
        private ScalarQuantizer quantizer;
        private com.spectrayan.spector.index.VectorIndex semanticIndex;
        private long inhibitionTtlMs = 300_000L;
        private float inhibitionFloor = 0.1f;
        private IcnuWeights icnuWeights;
        private boolean pinSourceEpisodes = false;
        private int pinnedQuota = 10_000;
        private com.spectrayan.spector.memory.pipeline.TagExtractor tagExtractor;
        private com.spectrayan.spector.storage.VectorStore vectorStore;
        private CognitiveProfileConfig profileConfig = CognitiveProfileConfig.allEnabled();

        // 3-Layer Cognitive Graph configuration
        private int hebbianGraphCapacity = 0; // 0 = use episodicPartitionCapacity
        private int temporalChainCapacity = 0; // 0 = use hebbianGraphCapacity
        private EntityExtractionMode entityExtractionMode = EntityExtractionMode.NONE;
        private EntityExtractor entityExtractor;
        private int entityGraphCapacity = 50_000;
        private int maxEntitiesPerMemory = 10;
        private int maxRelationsPerMemory = 20;

        public Builder dimensions(int dimensions) { this.dimensions = dimensions; return this; }
        public Builder embeddingProvider(EmbeddingProvider p) { this.embeddingProvider = p; return this; }
        public Builder persistence(Path p) { this.persistencePath = p; return this; }
        /** Sets the persistence mode (default: {@link MemoryPersistenceMode#DISK}). */
        public Builder persistenceMode(MemoryPersistenceMode mode) { this.persistenceMode = mode; return this; }
        /** If true, Working memory is also persisted to disk in DISK mode (default: false). */
        public Builder persistWorkingMemory(boolean persist) { this.persistWorkingMemory = persist; return this; }
        public Builder reflectPolicy(CircadianPolicy p) { this.circadianPolicy = p; return this; }
        public Builder workingCapacity(int c) { this.workingCapacity = c; return this; }
        public Builder episodicPartitionCapacity(int c) { this.episodicPartitionCapacity = c; return this; }
        public Builder semanticCapacity(int c) { this.semanticCapacity = c; return this; }
        public Builder proceduralCapacity(int c) { this.proceduralCapacity = c; return this; }
        public Builder surpriseWarmup(int w) { this.surpriseWarmup = w; return this; }
        public Builder flashbulbThreshold(double t) { this.flashbulbThreshold = t; return this; }
        public Builder valenceLearningRate(float r) { this.valenceLearningRate = r; return this; }
        public Builder deduplicationRadius(float r) { this.deduplicationRadius = r; return this; }
        public Builder textGenerationProvider(TextGenerationProvider p) { this.textGenerationProvider = p; return this; }
        public Builder quantizer(ScalarQuantizer quantizer) { this.quantizer = quantizer; return this; }

        /** Optional HNSW/IVF index for fused semantic recall (default: null = header-only fallback). */
        public Builder semanticIndex(com.spectrayan.spector.index.VectorIndex idx) { this.semanticIndex = idx; return this; }

        /** Engine's VectorStore for store-backed HNSW population (default: null). */
        public Builder vectorStore(com.spectrayan.spector.storage.VectorStore vs) { this.vectorStore = vs; return this; }

        /** Inhibition of Return TTL in millis (default: 300_000 = 5 minutes). */
        public Builder inhibitionTtlMs(long ms) { this.inhibitionTtlMs = ms; return this; }

        /** Inhibition of Return floor multiplier (default: 0.1). */
        public Builder inhibitionFloor(float floor) { this.inhibitionFloor = floor; return this; }

        /** ICNU fusion weights for neurodivergent importance computation (default: IcnuWeights.DEFAULT). */
        public Builder icnuWeights(IcnuWeights w) { this.icnuWeights = w; return this; }

        /** Enable lossless consolidation — pin source episodes during REM sleep (default: false). */
        public Builder pinSourceEpisodes(boolean pin) { this.pinSourceEpisodes = pin; return this; }

        /** Maximum number of pinned records (default: 10,000). */
        public Builder pinnedQuota(int quota) { this.pinnedQuota = quota; return this; }

        /** Pluggable tag extraction strategy for cognitive ingestion (default: ContentTagExtractor). */
        public Builder tagExtractor(com.spectrayan.spector.memory.pipeline.TagExtractor te) { this.tagExtractor = te; return this; }

        /** Cognitive profile configuration (default: all profiles enabled). */
        public Builder profileConfig(CognitiveProfileConfig config) { this.profileConfig = config; return this; }

        // ── 3-Layer Cognitive Graph configuration ──

        /** Hebbian graph capacity (default: same as episodicPartitionCapacity). */
        public Builder hebbianGraphCapacity(int c) { this.hebbianGraphCapacity = c; return this; }

        /** Temporal chain capacity (default: same as hebbianGraphCapacity). */
        public Builder temporalChainCapacity(int c) { this.temporalChainCapacity = c; return this; }

        /** Entity extraction mode (default: NONE). */
        public Builder entityExtractionMode(EntityExtractionMode mode) { this.entityExtractionMode = mode; return this; }

        /** Custom entity extractor (used when mode = CUSTOM). */
        public Builder entityExtractor(EntityExtractor extractor) { this.entityExtractor = extractor; return this; }

        /** Entity graph capacity — max entities (default: 50,000). */
        public Builder entityGraphCapacity(int c) { this.entityGraphCapacity = c; return this; }

        /** Max entities to extract per memory (default: 10). */
        public Builder maxEntitiesPerMemory(int c) { this.maxEntitiesPerMemory = c; return this; }

        /** Max relations to extract per memory (default: 20). */
        public Builder maxRelationsPerMemory(int c) { this.maxRelationsPerMemory = c; return this; }

        /**
         * Parses a cognitive profile config from a YAML string value.
         * Supports: "ALL", "CORE_ONLY", "WITH_NEURODIVERGENT", or comma-separated profile names.
         * @see CognitiveProfileConfig#fromConfigValue(String)
         */
        public Builder cognitiveProfiles(String configValue) { this.profileConfig = CognitiveProfileConfig.fromConfigValue(configValue); return this; }

        public SpectorMemory build() {
            if (dimensions <= 0 && embeddingProvider != null) {
                dimensions = embeddingProvider.dimensions();
            }
            return new DefaultSpectorMemory(this);
        }
    }
}
