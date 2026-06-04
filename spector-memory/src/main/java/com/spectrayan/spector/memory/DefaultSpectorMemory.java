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
import com.spectrayan.spector.memory.cortex.PartitionedSemanticStore;
import com.spectrayan.spector.memory.cortex.SemanticMemoryStore;
import com.spectrayan.spector.memory.cortex.StorageMigrator;
import com.spectrayan.spector.memory.cortex.PartitionLayoutMigrator;
import com.spectrayan.spector.memory.cortex.SemanticRecallStrategy;
import com.spectrayan.spector.memory.cortex.TierRouter;
import com.spectrayan.spector.memory.cortex.WorkingMemoryStore;
import com.spectrayan.spector.memory.cortex.MemoryBM25Index;
import com.spectrayan.spector.memory.cortex.TextDataStore;
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
import com.spectrayan.spector.memory.pipeline.GraphScoringPolicy;
import com.spectrayan.spector.memory.prospective.ProspectiveScheduler;
import com.spectrayan.spector.memory.prospective.Reminder;
import com.spectrayan.spector.memory.sync.MemoryWal;
import com.spectrayan.spector.memory.sync.WalEvent;
import com.spectrayan.spector.memory.synapse.ActRActivation;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.namespace.SpectorNamespaceManager;
import com.spectrayan.spector.memory.namespace.NamespaceQuotas;
import com.spectrayan.spector.memory.temporal.TemporalChain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.ErrorCode;

import com.spectrayan.spector.memory.error.SpectorGraphDecayException;

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
    private volatile TierRouter tierRouter;  // volatile: swapped on partition roll
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
    private final int temporalRetentionDays;
    private final com.spectrayan.spector.memory.synapse.TwoFactorConfig twoFactorConfig;
    private final ExecutorService virtualExecutor;
    private final AtomicInteger episodicIngestCount = new AtomicInteger(0);

    // ── Multi-Tenant Namespace ──
    private final SpectorNamespaceManager namespaceManager;

    // ── Partition Rolling Config (retained for rollPartition) ──
    private final Path basePath;
    private final int quantizedVecBytes;
    private final int semanticCapacity;
    private final int episodicPartitionCapacity;
    private final int proceduralCapacity;
    private volatile Path activePartitionDir;  // volatile: updated on partition roll
    private final Object partitionRollLock = new Object();

    private DefaultSpectorMemory(Builder builder) {
        this.dimensions = builder.dimensions;
        this.persistenceMode = builder.persistenceMode;
        this.persistencePath = builder.persistencePath;
        this.temporalRetentionDays = builder.temporalRetentionDays;
        this.twoFactorConfig = builder.twoFactorConfig;
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

        // ── Auto-migrate legacy layout → colocated partitions ──
        if (isDisk && basePath != null) {
            PartitionLayoutMigrator.migrate(basePath);
        }

        // ── Namespace Manager (multi-tenant isolation) ──
        if (isDisk && basePath != null) {
            this.namespaceManager = new SpectorNamespaceManager(basePath);
            log.info("NamespaceManager initialized: {} namespaces discovered", namespaceManager.count());
        } else {
            this.namespaceManager = null;
        }

        // ══════════════════════════════════════════════════════════════
        // COLOCATED PARTITION LAYOUT
        // ══════════════════════════════════════════════════════════════
        // In DISK mode, all tier stores and graphs live inside partition
        // directories under basePath/partitions/NNN_epoch/. Global data
        // (working memory, WAL, coactivation tracker) lives in basePath/global/.
        //
        // On a fresh start with no existing data, we create partition 000.

        int quantizedVecBytes = dimensions;

        // Store partition rolling config
        this.basePath = basePath;
        this.quantizedVecBytes = quantizedVecBytes;
        this.semanticCapacity = builder.semanticCapacity;
        this.episodicPartitionCapacity = builder.episodicPartitionCapacity;
        this.proceduralCapacity = builder.proceduralCapacity;

        // ── Resolve the active partition directory (DISK mode) ──
        Path resolvedPartitionDir = null;
        if (isDisk && basePath != null) {
            try {
                // Ensure directory structure exists
                java.nio.file.Files.createDirectories(StorageLayout.globalDir(basePath));
                java.nio.file.Files.createDirectories(StorageLayout.partitionsDir(basePath));

                // Discover existing partitions
                resolvedPartitionDir = discoverOrCreatePartition(basePath);
                log.info("Active partition: {}", resolvedPartitionDir.getFileName());
            } catch (java.io.IOException e) {
                log.error("Failed to initialize partition layout: {}", e.getMessage(), e);
                resolvedPartitionDir = null;
            }
        }
        this.activePartitionDir = resolvedPartitionDir;

        // ── Working memory: always global (not partitioned) ──
        WorkingMemoryStore workingStore;
        if (isDisk && builder.persistWorkingMemory && basePath != null) {
            workingStore = new WorkingMemoryStore(quantizedVecBytes, builder.workingCapacity,
                    StorageLayout.workingMem(basePath));
        } else {
            workingStore = new WorkingMemoryStore(quantizedVecBytes, builder.workingCapacity);
        }

        // ── Tier stores: created inside the active partition directory ──
        if (isDisk && basePath != null && activePartitionDir != null) {
            // Episodic: inside partition
            Path episodicPath = StorageLayout.episodicMem(activePartitionDir);
            EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(
                    episodicPath.getParent(), quantizedVecBytes, builder.episodicPartitionCapacity);

            // Procedural: inside partition
            ProceduralMemoryStore proceduralStore = new ProceduralMemoryStore(
                    quantizedVecBytes, builder.proceduralCapacity,
                    StorageLayout.proceduralMem(activePartitionDir));

            // Semantic: single file inside partition dir (partitioning is at directory level)
            SemanticMemoryStore semanticStore = new SemanticMemoryStore(
                    quantizedVecBytes, builder.semanticCapacity,
                    StorageLayout.semanticMem(activePartitionDir));
            this.tierRouter = new TierRouter(workingStore, episodicStore, semanticStore, proceduralStore);
        } else {
            // IN_MEMORY mode: use standard in-memory stores
            Path episodicPath = Path.of(System.getProperty("java.io.tmpdir"),
                    "spector-memory-" + ProcessHandle.current().pid() + "-" + System.nanoTime(),
                    "episodic");
            EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(
                    episodicPath, quantizedVecBytes, builder.episodicPartitionCapacity);
            ProceduralMemoryStore proceduralStore = new ProceduralMemoryStore(
                    quantizedVecBytes, builder.proceduralCapacity);
            SemanticMemoryStore semanticStore = new SemanticMemoryStore(
                    quantizedVecBytes, builder.semanticCapacity);
            this.tierRouter = new TierRouter(workingStore, episodicStore, semanticStore, proceduralStore);
        }

        // ── Memory Index (load from partition or global, keeping backward compat) ──
        if (isDisk && basePath != null && activePartitionDir != null) {
            // Try partition-local index first, fall back to legacy global index
            Path partitionIndex = StorageLayout.indexMidx(activePartitionDir);
            Path legacyIndex = StorageLayout.legacyIndex(basePath);
            if (java.nio.file.Files.exists(partitionIndex)) {
                this.index = MemoryIndex.load(partitionIndex);
            } else if (java.nio.file.Files.exists(legacyIndex)) {
                this.index = MemoryIndex.load(legacyIndex);
                log.info("Loaded legacy memory-index.mem — will save to partition on next close");
            } else {
                this.index = new MemoryIndex();
            }
        } else {
            this.index = new MemoryIndex();
        }

        // ── WAL (file-backed in global/ directory) ──
        if (isDisk && basePath != null) {
            this.wal = new MemoryWal(StorageLayout.walDir(basePath));
        } else {
            this.wal = new MemoryWal();
        }

        // ── Biological Subsystems ──
        SurpriseDetector surpriseDetector = new SurpriseDetector(builder.surpriseWarmup);
        FlashbulbPolicy flashbulbPolicy = new FlashbulbPolicy(builder.flashbulbThreshold);
        this.valenceTracker = new ValenceTracker(builder.valenceLearningRate);
        // CoActivationTracker: global (load from global/ dir)
        if (isDisk && basePath != null) {
            this.coActivationTracker = CoActivationTracker.load(
                    StorageLayout.coactivationTracker(basePath), 10_000, 20_000);
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

        // ── 3-Layer Cognitive Graph (inside partition directory) ──
        int graphCapacity = builder.hebbianGraphCapacity > 0
                ? builder.hebbianGraphCapacity : builder.episodicPartitionCapacity;

        // HebbianGraph: load from partition, fall back to legacy basePath
        if (isDisk && basePath != null && activePartitionDir != null) {
            Path partGraph = StorageLayout.hebbianGraph(activePartitionDir);
            Path legacyGraph = basePath.resolve(StorageLayout.FILE_HEBBIAN);
            this.hebbianGraph = HebbianGraph.load(
                    java.nio.file.Files.exists(partGraph) ? partGraph : legacyGraph, graphCapacity);
        } else {
            this.hebbianGraph = new HebbianGraph(graphCapacity);
        }

        // TemporalChain: load from partition, fall back to legacy basePath
        int temporalCapacity = builder.temporalChainCapacity > 0
                ? builder.temporalChainCapacity : graphCapacity;
        if (isDisk && basePath != null && activePartitionDir != null) {
            Path partChain = StorageLayout.temporalChain(activePartitionDir);
            Path legacyChain = basePath.resolve(StorageLayout.FILE_TEMPORAL);
            this.temporalChain = TemporalChain.load(
                    java.nio.file.Files.exists(partChain) ? partChain : legacyChain, temporalCapacity);
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
            if (isDisk && basePath != null && activePartitionDir != null) {
                Path partEntity = StorageLayout.entityGraph(activePartitionDir);
                Path legacyEntity = basePath.resolve(StorageLayout.FILE_ENTITY);
                this.entityGraph = EntityGraph.load(
                        java.nio.file.Files.exists(partEntity) ? partEntity : legacyEntity, entityCap, edgeCap);
            } else {
                this.entityGraph = new EntityGraph(entityCap, edgeCap);
            }
        } else {
            this.entityGraph = null;
        }

        // ── Pipelines ──
        this.embeddingProvider = embeddingProvider;

        // ── BM25 Text Search (inside partition directory) ──
        MemoryBM25Index bm25Index;
        TextDataStore textDataStore;
        int activePartitionIndex;
        if (isDisk && basePath != null && activePartitionDir != null) {
            // text.dat inside partition
            textDataStore = new TextDataStore(StorageLayout.textDat(activePartitionDir));
            bm25Index = new MemoryBM25Index(1); // start with 1 partition

            // Rebuild BM25 from MemoryIndex texts loaded from index
            Map<String, String> allTexts = new java.util.HashMap<>();
            for (var entry : index.locationMap().entrySet()) {
                String text = index.text(entry.getKey());
                if (text != null && !text.isEmpty()) {
                    allTexts.put(entry.getKey(), text);
                }
            }
            if (!allTexts.isEmpty()) {
                bm25Index.rebuildPartition(0, allTexts);
                log.info("Rebuilt BM25 index with {} documents from memory index", allTexts.size());
            }
            activePartitionIndex = 0;
        } else {
            // IN_MEMORY mode: still support BM25 for remember/recall within session
            bm25Index = new MemoryBM25Index(1);
            textDataStore = null;
            activePartitionIndex = 0;
        }

        this.cognitiveTarget = new CognitiveIngestionTarget(
                quantizer, surpriseDetector, flashbulbPolicy,
                tierRouter, index, wal, workingStore, builder.icnuWeights,
                builder.semanticIndex, builder.vectorStore, builder.tagExtractor, true,
                hebbianGraph, temporalChain, entityExtractor, entityGraph,
                bm25Index, textDataStore, activePartitionIndex);

        // Wire automatic partition rolling: when any tier store is full,
        // roll to a new colocated partition directory and retry the write
        if (isDisk) {
            this.cognitiveTarget.setPartitionRollCallback(this::rollPartition);
        }

        // Build optional fused semantic recall strategy
        SemanticRecallStrategy semanticStrategy = null;
        if (builder.semanticIndex != null && tierRouter.semantic() != null) {
            semanticStrategy = new SemanticRecallStrategy(builder.semanticIndex, tierRouter.semantic(), index);
        }

        this.recallPipeline = new RecallPipeline(
                embeddingProvider, tierRouter, index,
                suppressionSet, habituationPenalty, prospectiveScheduler, wal,
                quantizer.mins(), quantizer.scales(), semanticStrategy,
                null, hebbianGraph, temporalChain, entityGraph, entityExtractor,
                builder.graphScoringPolicy, bm25Index);

        // Register post-recall observers (Phase 6: Observer pattern)
        recallPipeline.addListener(new LtpReconsolidationListener(index, tierRouter, wal));
        recallPipeline.addListener(new HebbianCoActivationListener(coActivationTracker));

        log.info("SpectorMemory initialized: dimensions={}, model={}, persistence={}, mode={}, " +
                 "partition={}, quantizer={}",
                dimensions, embeddingProvider.modelName(),
                basePath != null ? basePath : "in-memory",
                persistenceMode,
                activePartitionDir != null ? activePartitionDir.getFileName() : "none",
                builder.quantizer != null ? "user-provided" : "identity-default");
    }

    /**
     * Discovers existing partitions or creates partition 000 if none exist.
     *
     * @param basePath the memory persistence root
     * @return path to the active (latest) partition directory
     */
    private static Path discoverOrCreatePartition(Path basePath) throws java.io.IOException {
        Path partitionsDir = StorageLayout.partitionsDir(basePath);
        java.nio.file.Files.createDirectories(partitionsDir);

        // Scan for existing partition directories
        Path latestPartition = null;
        int maxSeq = -1;
        try (var stream = java.nio.file.Files.newDirectoryStream(partitionsDir)) {
            for (Path dir : stream) {
                if (!java.nio.file.Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                if (StorageLayout.isPartitionDir(name)) {
                    int seq = StorageLayout.parsePartitionSeqNo(name);
                    if (seq > maxSeq) {
                        maxSeq = seq;
                        latestPartition = dir;
                    }
                }
            }
        }

        if (latestPartition != null) {
            return latestPartition;
        }

        // No partitions found → create partition 000
        long epochSecs = java.time.Instant.now().getEpochSecond();
        Path newPartition = StorageLayout.partitionDir(basePath, 0, epochSecs);
        java.nio.file.Files.createDirectories(newPartition);
        log.info("Created initial partition: {}", newPartition.getFileName());
        return newPartition;
    }

    /**
     * Rolls to a new colocated partition directory.
     *
     * <p>Called automatically when a tier store reaches capacity during ingestion.
     * Creates a new partition directory, fresh tier stores, and atomically swaps
     * the TierRouter so subsequent writes go to the new partition.</p>
     *
     * <p>Thread-safe: synchronized on {@code partitionRollLock} so concurrent
     * ingestion threads see a consistent swap.</p>
     */
    void rollPartition() {
        synchronized (partitionRollLock) {
            if (basePath == null) {
                log.warn("Cannot roll partition — no basePath (IN_MEMORY mode)");
                return;
            }

            try {
                // Determine next sequence number
                Path partitionsDir = StorageLayout.partitionsDir(basePath);
                int maxSeq = -1;
                try (var stream = java.nio.file.Files.newDirectoryStream(partitionsDir)) {
                    for (Path dir : stream) {
                        if (java.nio.file.Files.isDirectory(dir)
                                && StorageLayout.isPartitionDir(dir.getFileName().toString())) {
                            maxSeq = Math.max(maxSeq,
                                    StorageLayout.parsePartitionSeqNo(dir.getFileName().toString()));
                        }
                    }
                }

                int nextSeq = maxSeq + 1;
                long epochSecs = java.time.Instant.now().getEpochSecond();
                Path newPartition = StorageLayout.partitionDir(basePath, nextSeq, epochSecs);
                java.nio.file.Files.createDirectories(newPartition);

                // Create fresh tier stores in new partition
                EpisodicMemoryStore newEpisodic = new EpisodicMemoryStore(
                        StorageLayout.episodicMem(newPartition).getParent(),
                        quantizedVecBytes, episodicPartitionCapacity);

                ProceduralMemoryStore newProcedural = new ProceduralMemoryStore(
                        quantizedVecBytes, proceduralCapacity,
                        StorageLayout.proceduralMem(newPartition));

                SemanticMemoryStore newSemantic = new SemanticMemoryStore(
                        quantizedVecBytes, semanticCapacity,
                        StorageLayout.semanticMem(newPartition));

                // Preserve working memory (global, not partitioned)
                WorkingMemoryStore workingStore = tierRouter.working();

                // Atomic swap
                this.tierRouter = new TierRouter(workingStore, newEpisodic, newSemantic, newProcedural);
                this.activePartitionDir = newPartition;

                // Update ingestion target's router reference
                cognitiveTarget.updateTierRouter(this.tierRouter);

                log.info("Rolled to new partition: {} (seq={}, semantic capacity={})",
                        newPartition.getFileName(), nextSeq, semanticCapacity);

            } catch (java.io.IOException e) {
                throw new SpectorServerException(ErrorCode.INTERNAL_ERROR,
                        "Failed to roll partition: " + e.getMessage(), e);
            }
        }
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
        return remember(id, text, type, source, null, tags);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source,
                                              com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                              String... tags) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Embed text, then pass to cognitive target with optional ICNU hints
                float[] vector = embeddingProvider.embed(text).vector();
                cognitiveTarget.ingestCognitive(id, text, vector, type, tags, source, hints);

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
        // Get the semantic TierStore (works for both single-file and partitioned modes)
        var semanticTarget = tierRouter.isSemanticPartitioned()
                ? tierRouter.semanticPartitioned() : tierRouter.semantic();
        ReflectReport report = reflectDaemon.runCycle(
                tierRouter.episodic(), semanticTarget,
                offset -> index.findTextByOffset(MemoryType.EPISODIC, offset));

        // ── Graph Decay (Sleep Consolidation) ──
        // Hebbian edges decay by 10% per reflection cycle (biological synaptic homeostasis)
        try {
            int hebbianDecayed = hebbianGraph.decayEdges(0.9f);
            if (hebbianDecayed > 0) {
                log.info("Reflect: Hebbian graph decayed {} weak edges", hebbianDecayed);
            }
        } catch (RuntimeException e) {
            SpectorGraphDecayException ex = new SpectorGraphDecayException("Hebbian edge decay", e);
            log.warn(ex.getMessage());
        }

        // Temporal chain: prune links older than configured retention period
        int temporalPruned = 0;
        if (temporalChain != null) {
            try {
                long cutoffMs = System.currentTimeMillis()
                        - (long) temporalRetentionDays * 24 * 60 * 60 * 1000;
                temporalPruned = temporalChain.pruneOlderThan(cutoffMs);
            } catch (RuntimeException e) {
                log.warn("Temporal chain pruning failed: {}", e.getMessage());
            }
        }

        // ── Cross-Layer Promotion: Hebbian → Entity ──
        // Strong Hebbian co-activation (weight ≥ 3.0) is promoted to entity-level
        // RELATED_TO edges. This bridges statistical correlation (Hebbian) with
        // structured knowledge (Entity graph).
        int crossPromoted = 0;
        try {
            crossPromoted = promoteHebbianToEntity(3.0f);
            if (crossPromoted > 0) {
                log.info("Reflect: cross-layer promoted {} Hebbian edges to entity relations",
                        crossPromoted);
            }
        } catch (RuntimeException e) {
            log.warn("Cross-layer promotion failed: {}", e.getMessage());
        }

        // ── Entity Graph Maintenance ──
        if (entityGraph != null && entityGraph.entityCount() > 0) {
            try {
                // Decay entity edges: 5% decay per cycle, prune below 0.5
                int entityDecayed = entityGraph.decayEdges(0.95f, 0.5f);
                if (entityDecayed > 0) {
                    log.info("Reflect: entity graph decayed {} weak edges", entityDecayed);
                }
                // Merge near-duplicate entities (Levenshtein distance ≤ 2)
                int entityMerged = entityGraph.mergeSimilarEntities(2);
                if (entityMerged > 0) {
                    log.info("Reflect: merged {} similar entities", entityMerged);
                }
            } catch (RuntimeException e) {
                log.warn("Entity graph maintenance failed: {}", e.getMessage());
            }
        }

        wal.append(WalEvent.EventType.REFLECT, "system", null);

        // Overlay temporal pruning count onto the daemon's report
        return new ReflectReport(
                report.consolidatedCount(), report.tombstonedCount(),
                report.compactedPartitions(), temporalPruned, report.duration());
    }

    /**
     * Promotes strong Hebbian co-activation edges into entity-level RELATED_TO edges.
     *
     * <p>For each Hebbian edge with weight ≥ {@code minWeight}, scans both endpoint
     * memories' entity associations and creates RELATED_TO edges between all entity
     * pairs. This bridges the statistical co-occurrence layer (Hebbian) with the
     * structured knowledge layer (Entity graph).</p>
     *
     * @param minWeight minimum Hebbian weight to qualify for promotion
     * @return number of entity relations created or strengthened
     */
    private int promoteHebbianToEntity(float minWeight) {
        if (entityGraph == null || entityGraph.entityCount() == 0) return 0;

        // Build reverse index: memoryIdx → List<entityId>
        // This converts O(C × 20 × E × R) → O(E × R + C × 20)
        // Previously findEntitiesForMemory scanned ALL entities for EACH Hebbian edge endpoint.
        int ecnt = entityGraph.entityCount();
        java.util.Map<Integer, java.util.List<Integer>> memToEntities = new java.util.HashMap<>();
        for (int e = 0; e < ecnt; e++) {
            int refCount = entityGraph.memoryRefCount(e);
            for (int r = 0; r < refCount; r++) {
                int memIdx = entityGraph.memoryRefAt(e, r);
                if (memIdx >= 0) {
                    memToEntities.computeIfAbsent(memIdx, k -> new java.util.ArrayList<>(2)).add(e);
                }
            }
        }

        int promoted = 0;
        int capacity = hebbianGraph.capacity();

        for (int nodeA = 0; nodeA < capacity; nodeA++) {
            var edges = hebbianGraph.neighbors(nodeA);
            for (var edge : edges) {
                if (edge.weight() < minWeight) break; // sorted descending, so remaining are weaker
                int nodeB = edge.neighborIndex();
                if (nodeB <= nodeA) continue; // avoid double-processing A↔B

                // O(1) lookup via reverse index
                var entitiesA = memToEntities.get(nodeA);
                var entitiesB = memToEntities.get(nodeB);

                if (entitiesA == null || entitiesB == null) continue;

                // Create RELATED_TO edges between all entity pairs
                for (int eA : entitiesA) {
                    for (int eB : entitiesB) {
                        if (eA != eB) {
                            entityGraph.addRelation(eA, eB,
                                    com.spectrayan.spector.memory.graph.RelationType.RELATED_TO);
                            promoted++;
                        }
                    }
                }
            }
        }
        return promoted;
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

            // ACT-R: record recall timestamp in ring buffer (V3 only)
            if (layout.headerLayout().version() >= 3) {
                long creationTs = layout.readTimestamp(segment, loc.offset());
                ActRActivation.recordRecall(segment, loc.offset(), creationTs, System.currentTimeMillis());
            }

            // Two-Factor Memory: update storage strength S(t) on successful retrieval
            // ΔS = sGain × (1 - R(t)) → max boost when retrieval was hard
            var headerLayout = layout.headerLayout();
            if (headerLayout.headerBytes() > 32) { // V2+ has storage_strength
                float currentS = headerLayout.readStorageStrength(segment, loc.offset());
                // Approximate R(t) from current decay bucket
                long timestamp = layout.readTimestamp(segment, loc.offset());
                int rawBucket = com.spectrayan.spector.memory.synapse.DecayStrategy.ageToBucket(
                        timestamp, System.currentTimeMillis());
                float currentR = com.spectrayan.spector.memory.synapse.DecayStrategy.decay(rawBucket);
                float deltaS = twoFactorConfig.sGain() * (1.0f - currentR);
                float newS = Math.min(currentS + deltaS, twoFactorConfig.sMax());
                headerLayout.writeStorageStrength(segment, loc.offset(), newS);
            }
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

    @Override
    public WhyNotExplanation whyNot(String memoryId, String queryText, RecallOptions options) {
        // Step 1: Does the memory exist at all?
        var loc = index.locate(memoryId);
        if (loc == null) {
            return new WhyNotExplanation(memoryId, queryText, false, false,
                    null, 0f, WhyNotExplanation.Reason.NOT_FOUND,
                    "Memory '" + memoryId + "' does not exist in the index.");
        }

        // Step 2: Is it tombstoned?
        var layout = tierRouter.layoutFor(loc.type());
        var segment = tierRouter.segmentFor(loc.type());
        if (layout != null && segment != null) {
            byte flags = segment.get(
                    com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.LAYOUT_FLAGS,
                    loc.offset() + com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.OFFSET_FLAGS);
            if (com.spectrayan.spector.memory.synapse.SynapticHeaderConstants.isTombstoned(flags)) {
                return new WhyNotExplanation(memoryId, queryText, true, false,
                        null, 0f, WhyNotExplanation.Reason.TOMBSTONED,
                        "Memory '" + memoryId + "' has been deleted (tombstone flag set).");
            }
        }

        // Step 3: Is it suppressed?
        if (suppressionSet.isSuppressed(memoryId)) {
            return new WhyNotExplanation(memoryId, queryText, true, true,
                    null, 0f, WhyNotExplanation.Reason.SUPPRESSED,
                    "Memory '" + memoryId + "' is in the suppression set. "
                    + "Use unsuppress(\"" + memoryId + "\") to allow recall.");
        }

        // Step 4: Run a full recall in OBSERVE mode (no mutations)
        int originalTopK = options != null ? options.topK() : 5;
        RecallOptions observeOptions = RecallOptions.builder()
                .recallMode(RecallMode.OBSERVE)
                .topK(Math.max(originalTopK, 20)) // wider net to find the missing memory
                .build();

        List<CognitiveResult> results = recall(queryText, observeOptions);

        // Step 5: Is the memory in the results?
        CognitiveResult found = null;
        for (CognitiveResult r : results) {
            if (memoryId.equals(r.id())) {
                found = r;
                break;
            }
        }

        if (found != null) {
            // Memory WAS returned — it's in the top-K.
            // This means it was probably outranked for a smaller topK.
            float cutoffScore = results.isEmpty() ? 0f : results.get(results.size() - 1).score();
            return new WhyNotExplanation(memoryId, queryText, true, false,
                    found.breakdown(), 0f, WhyNotExplanation.Reason.OUTRANKED,
                    "Memory '" + memoryId + "' WAS found in extended recall "
                    + "(score=" + String.format("%.4f", found.score()) + "). "
                    + "It may have been outside your original topK cutoff.");
        }

        // Step 6: Memory exists but wasn't in results — it was pre-filtered or outranked
        float cutoffScore = results.isEmpty() ? 0f : results.get(results.size() - 1).score();
        return new WhyNotExplanation(memoryId, queryText, true, false,
                null, cutoffScore, WhyNotExplanation.Reason.FILTERED,
                "Memory '" + memoryId + "' was eliminated by pre-filters "
                + "(tag gate, valence range, importance floor, or time decay). "
                + "TopK cutoff score: " + String.format("%.4f", cutoffScore) + ". "
                + (cutoffScore > 0 ? "Check if tags/valence/importance match your query options." : ""));
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

    /** Returns the namespace manager (null if IN_MEMORY mode). */
    public SpectorNamespaceManager namespaceManager() { return namespaceManager; }

    @Override
    public void close() {
        log.info("SpectorMemory closing ({} total memories, mode={})", totalMemories(), persistenceMode);

        // Save to partition directory (colocated layout) or legacy paths
        if (persistenceMode == MemoryPersistenceMode.DISK && persistencePath != null) {
            // Determine save location: prefer partition dir, fall back to basePath
            Path saveDir = activePartitionDir != null ? activePartitionDir : persistencePath;

            try {
                // Index: save to partition-local index.midx
                Path indexPath = activePartitionDir != null
                        ? StorageLayout.indexMidx(activePartitionDir)
                        : persistencePath.resolve(StorageLayout.LEGACY_FILE_INDEX);
                index.save(indexPath);
            } catch (Exception e) {
                log.error("Failed to save MemoryIndex on close: {}", e.getMessage(), e);
            }

            // Save 3-Layer Cognitive Graph to partition
            try {
                hebbianGraph.save(StorageLayout.hebbianGraph(saveDir));
            } catch (Exception e) {
                log.error("Failed to save HebbianGraph on close: {}", e.getMessage(), e);
            }
            try {
                temporalChain.save(StorageLayout.temporalChain(saveDir));
            } catch (Exception e) {
                log.error("Failed to save TemporalChain on close: {}", e.getMessage(), e);
            }
            if (entityGraph != null) {
                try {
                    entityGraph.save(StorageLayout.entityGraph(saveDir));
                } catch (Exception e) {
                    log.error("Failed to save EntityGraph on close: {}", e.getMessage(), e);
                }
            }
            // CoActivation tracker: always global
            try {
                coActivationTracker.save(StorageLayout.coactivationTracker(persistencePath));
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
        private int semanticCapacity = 10_000;  // 10K per partition (~40MB at 4160B/record)
        private int nodesPerPartition = 10_000;
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
        private GraphScoringPolicy graphScoringPolicy = GraphScoringPolicy.DEFAULT;
        private int temporalRetentionDays = 7;
        private com.spectrayan.spector.memory.synapse.TwoFactorConfig twoFactorConfig
                = com.spectrayan.spector.memory.synapse.TwoFactorConfig.DEFAULT;

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
        /** Nodes per semantic partition before rolling to a new file (default: 10,000). */
        public Builder nodesPerPartition(int n) { this.nodesPerPartition = n; return this; }
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

        /** Graph scoring policy — configurable weights for cognitive graph steps (default: GraphScoringPolicy.DEFAULT). */
        public Builder graphScoringPolicy(GraphScoringPolicy policy) { this.graphScoringPolicy = policy; return this; }

        /** Temporal chain retention in days — links older than this are pruned during reflect() (default: 7). */
        public Builder temporalRetentionDays(int days) { this.temporalRetentionDays = days; return this; }

        /** Two-Factor Memory (Bjork & Bjork) configuration (default: TwoFactorConfig.DEFAULT). */
        public Builder twoFactorConfig(com.spectrayan.spector.memory.synapse.TwoFactorConfig config) { this.twoFactorConfig = config; return this; }

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
