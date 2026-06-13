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

import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.core.quantization.ScalarQuantizer;
import com.spectrayan.spector.embed.EmbedConfig;
import com.spectrayan.spector.embed.EmbeddingProvider;
import com.spectrayan.spector.embed.ParallelEmbeddingPipeline;
import com.spectrayan.spector.embed.PipelineEmbeddingResult;
import com.spectrayan.spector.embed.SparseEncodingProvider;
import com.spectrayan.spector.embed.TextGenerationProvider;
import com.spectrayan.spector.embed.TokenEmbeddingProvider;
import com.spectrayan.spector.index.ColBERTReranker;
import com.spectrayan.spector.index.ColBERTTokenCache;
import com.spectrayan.spector.memory.amygdala.ValenceTracker;
import com.spectrayan.spector.memory.cortex.CentroidRouter;
import com.spectrayan.spector.memory.cortex.EpisodicMemoryStore;
import com.spectrayan.spector.memory.cortex.MemorySource;
import com.spectrayan.spector.memory.cortex.ProceduralMemoryStore;
import com.spectrayan.spector.memory.cortex.SemanticMemoryStore;
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
import com.spectrayan.spector.memory.model.CognitiveProfile;
import com.spectrayan.spector.memory.model.CognitiveResult;
import com.spectrayan.spector.memory.model.ImportanceEstimate;
import com.spectrayan.spector.memory.model.IngestionContext;
import com.spectrayan.spector.memory.model.MemoryPersistenceMode;
import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.model.RecallMode;
import com.spectrayan.spector.memory.model.RecallOptions;
import com.spectrayan.spector.memory.model.ReflectReport;
import com.spectrayan.spector.memory.model.WhyNotExplanation;
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
import com.spectrayan.spector.memory.sync.CheckpointDaemon;
import com.spectrayan.spector.memory.sync.CompactionResult;
import com.spectrayan.spector.memory.sync.VacuumCompactor;
import com.spectrayan.spector.commons.concurrent.DaemonSupervisor;
import com.spectrayan.spector.commons.concurrent.DaemonPolicy;
import com.spectrayan.spector.memory.synapse.ActRActivation;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.SynapticHeaderConstants;
import com.spectrayan.spector.memory.namespace.SpectorNamespaceManager;
import com.spectrayan.spector.memory.namespace.NamespaceQuotas;
import com.spectrayan.spector.memory.temporal.TemporalChain;
import com.spectrayan.spector.commons.TextChunker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.SpectorServerException;
import com.spectrayan.spector.commons.error.ErrorCode;

import com.spectrayan.spector.memory.error.SpectorGraphDecayException;
import com.spectrayan.spector.memory.id.IdStrategy;
import com.spectrayan.spector.memory.id.MemoryIdGenerator;
import com.spectrayan.spector.memory.model.CognitiveRecord;

/**
 * Default implementation of {@link SpectorMemory} — the Zero-GC Cognitive Backbone for Autonomous Agents.
 *
 * <h3>Design Pattern: Façade</h3>
 * <p>{@code DefaultSpectorMemory} is a thin façade that composes and delegates to focused subsystems:</p>
 * <ul>
 *   <li>{@link CognitiveIngestionTarget} — 10-step ingest (embed → quantize → route → WAL)</li>
 *   <li>{@link RecallPipeline} — 8-step recall (embed → score → filter → sort)</li>
 *   <li>{@link PartitionManager} — DISK partition discovery, creation, and rolling</li>
 *   <li>{@link ImportanceEstimator} — read-only novelty/ICNU/flashbulb pipeline</li>
 *   <li>{@link ReflectionOrchestrator} — sleep consolidation, graph decay, cross-layer promotion</li>
 *   <li>{@link ReinforcementHandler} — valence, LTP, ACT-R, Two-Factor, ICNU re-fusion</li>
 *   <li>{@link PersistenceManager} — flush-on-close and resource cleanup</li>
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
public final class DefaultSpectorMemory implements SpectorMemory, SpectorMemoryAdmin {

    private static final Logger log = LoggerFactory.getLogger(DefaultSpectorMemory.class);

    // ── Core Subsystems (Façade composition) ──
    private final CognitiveIngestionTarget cognitiveTarget;
    private final EmbeddingProvider embeddingProvider;
    private final RecallPipeline recallPipeline;
    private final MemoryIndex index;
    private final ScalarQuantizer quantizer;

    // ── Extracted Strategy/Handler Components ──
    private final PartitionManager partitionManager;     // owns volatile tierRouter
    private final ImportanceEstimator importanceEstimator;
    private final ReflectionOrchestrator reflectionOrchestrator;
    private final ReinforcementHandler reinforcementHandler;

    // ── Biological Subsystems ──
    private final ValenceTracker valenceTracker;
    private final CoActivationTracker coActivationTracker;
    private final SuppressionSet suppressionSet;
    private final HabituationPenalty habituationPenalty;
    private final ProspectiveScheduler prospectiveScheduler;
    private final MemoryIntrospector introspector;
    private final LateralEvaluator lateralEvaluator;
    private final MemoryWal wal;

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

    // ── Multi-Tenant Namespace ──
    private final SpectorNamespaceManager namespaceManager;

    // ── ID Generation ──
    private final MemoryIdGenerator idGenerator;

    // ── Circadian trigger counter ──
    private final AtomicInteger episodicIngestCount = new AtomicInteger(0);

    // ── Automatic Checkpointing ──
    private final CheckpointDaemon checkpointDaemon;
    private final DaemonSupervisor daemonSupervisor;

    // ── Shutdown Hook (auto-registered for DISK mode) ──
    private final Thread shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ── Chunking for remember() ──
    private final TextChunker chunker;
    private final ParallelEmbeddingPipeline parallelPipeline;

    // ── Multimodal Attachment Processing ──
    private final com.spectrayan.spector.memory.pipeline.AttachmentProcessor attachmentProcessor;

    private DefaultSpectorMemory(Builder builder) {
        this.dimensions = builder.dimensions;
        this.persistenceMode = builder.persistenceMode;
        this.persistencePath = builder.persistencePath;
        this.circadianPolicy = builder.circadianPolicy;
        this.profileConfig = builder.profileConfig;
        this.chunker = builder.chunker;

        if (builder.embeddingProvider == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL,
                    "embeddingProvider is required");
        }
        EmbeddingProvider embeddingProvider = builder.embeddingProvider;
        this.embeddingProvider = embeddingProvider;
        this.parallelPipeline = new ParallelEmbeddingPipeline(embeddingProvider);

        boolean isDisk = persistenceMode == MemoryPersistenceMode.DISK;

        // ── Resolve persistence path ──
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

        // ── Quantizer ──
        if (builder.quantizer != null) {
            this.quantizer = builder.quantizer;
        } else {
            float[] defaultMins = new float[builder.dimensions];
            float[] defaultMaxs = new float[builder.dimensions];
            java.util.Arrays.fill(defaultMins, -1.0f);
            java.util.Arrays.fill(defaultMaxs, 1.0f);
            this.quantizer = ScalarQuantizer.fromBounds(builder.dimensions, defaultMins, defaultMaxs);
        }

        // ── Auto-migrate legacy layout ──
        if (isDisk && basePath != null) {
            PartitionLayoutMigrator.migrate(basePath);
        }

        // ── Namespace Manager ──
        if (isDisk && basePath != null) {
            this.namespaceManager = new SpectorNamespaceManager(basePath);
            log.info("NamespaceManager initialized: {} namespaces discovered", namespaceManager.count());
        } else {
            this.namespaceManager = null;
        }

        // ── Partition layout ──
        int quantizedVecBytes = builder.dimensions;

        Path resolvedPartitionDir = null;
        if (isDisk && basePath != null) {
            try {
                java.nio.file.Files.createDirectories(StorageLayout.globalDir(basePath));
                java.nio.file.Files.createDirectories(StorageLayout.partitionsDir(basePath));
                resolvedPartitionDir = PartitionManager.discoverOrCreatePartition(basePath);
                log.info("Active partition: {}", resolvedPartitionDir.getFileName());
            } catch (java.io.IOException e) {
                log.error("Failed to initialize partition layout: {}", e.getMessage(), e);
            }
        }

        // ── Tier stores ──
        TierRouter tierRouter;
        WorkingMemoryStore workingStore;
        if (isDisk && builder.persistWorkingMemory && basePath != null) {
            workingStore = new WorkingMemoryStore(quantizedVecBytes, builder.workingCapacity,
                    StorageLayout.workingMem(basePath));
        } else {
            workingStore = new WorkingMemoryStore(quantizedVecBytes, builder.workingCapacity);
        }

        if (isDisk && basePath != null && resolvedPartitionDir != null) {
            EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(
                    StorageLayout.episodicMem(resolvedPartitionDir),
                    quantizedVecBytes, builder.episodicPartitionCapacity);
            ProceduralMemoryStore proceduralStore = new ProceduralMemoryStore(
                    quantizedVecBytes, builder.proceduralCapacity,
                    StorageLayout.proceduralMem(resolvedPartitionDir));
            SemanticMemoryStore semanticStore = new SemanticMemoryStore(
                    quantizedVecBytes, builder.semanticCapacity,
                    StorageLayout.semanticMem(resolvedPartitionDir));
            tierRouter = new TierRouter(workingStore, episodicStore, semanticStore, proceduralStore);
        } else {
            EpisodicMemoryStore episodicStore = new EpisodicMemoryStore(
                    quantizedVecBytes, builder.episodicPartitionCapacity);
            ProceduralMemoryStore proceduralStore = new ProceduralMemoryStore(
                    quantizedVecBytes, builder.proceduralCapacity);
            SemanticMemoryStore semanticStore = new SemanticMemoryStore(
                    quantizedVecBytes, builder.semanticCapacity);
            tierRouter = new TierRouter(workingStore, episodicStore, semanticStore, proceduralStore);
        }

        // ── Memory Index ──
        if (isDisk && basePath != null && resolvedPartitionDir != null) {
            Path partitionIndex = StorageLayout.indexMidx(resolvedPartitionDir);
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

        // ── WAL ──
        if (isDisk && basePath != null) {
            this.wal = new MemoryWal(StorageLayout.walDir(basePath));
        } else {
            this.wal = new MemoryWal();
        }

        // ── Biological Subsystems ──
        SurpriseDetector surpriseDetector = new SurpriseDetector(builder.surpriseWarmup);
        IcnuWeights icnuWeights = builder.icnuWeights != null ? builder.icnuWeights : IcnuWeights.DEFAULT;
        FlashbulbPolicy flashbulbPolicy = new FlashbulbPolicy(builder.flashbulbThreshold);
        this.valenceTracker = new ValenceTracker(builder.valenceLearningRate);

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

        ReflectDaemon reflectDaemon = new ReflectDaemon(
                builder.circadianPolicy,
                builder.dimensions > 0 ? new CentroidRouter(builder.dimensions) : null,
                builder.textGenerationProvider,
                embeddingProvider,
                5, // minClusterSize
                builder.pinSourceEpisodes,
                builder.pinnedQuota);

        // ── 3-Layer Cognitive Graph ──
        int graphCapacity = builder.hebbianGraphCapacity > 0
                ? builder.hebbianGraphCapacity : builder.episodicPartitionCapacity;

        if (isDisk && basePath != null && resolvedPartitionDir != null) {
            Path partGraph = StorageLayout.hebbianGraph(resolvedPartitionDir);
            Path legacyGraph = basePath.resolve(StorageLayout.FILE_HEBBIAN);
            this.hebbianGraph = HebbianGraph.load(
                    java.nio.file.Files.exists(partGraph) ? partGraph : legacyGraph, graphCapacity);
        } else {
            this.hebbianGraph = new HebbianGraph(graphCapacity);
        }

        int temporalCapacity = builder.temporalChainCapacity > 0
                ? builder.temporalChainCapacity : graphCapacity;
        if (isDisk && basePath != null && resolvedPartitionDir != null) {
            Path partChain = StorageLayout.temporalChain(resolvedPartitionDir);
            Path legacyChain = basePath.resolve(StorageLayout.FILE_TEMPORAL);
            this.temporalChain = TemporalChain.load(
                    java.nio.file.Files.exists(partChain) ? partChain : legacyChain, temporalCapacity);
        } else {
            this.temporalChain = new TemporalChain(temporalCapacity);
        }

        EntityExtractor entityExtractor;
        if (builder.entityExtractionMode == EntityExtractionMode.LLM
                && builder.textGenerationProvider != null) {
            entityExtractor = new LlmEntityExtractor(
                    builder.textGenerationProvider,
                    builder.maxEntitiesPerMemory, builder.maxRelationsPerMemory,
                    builder.llmGenerationOptions);
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
            if (isDisk && basePath != null && resolvedPartitionDir != null) {
                Path partEntity = StorageLayout.entityGraph(resolvedPartitionDir);
                Path legacyEntity = basePath.resolve(StorageLayout.FILE_ENTITY);
                this.entityGraph = EntityGraph.load(
                        java.nio.file.Files.exists(partEntity) ? partEntity : legacyEntity, entityCap, edgeCap);
            } else {
                this.entityGraph = new EntityGraph(entityCap, edgeCap);
            }
        } else {
            this.entityGraph = null;
        }

        // ── BM25 Text Search ──
        MemoryBM25Index bm25Index;
        TextDataStore textDataStore;
        int activePartitionIndex;
        if (isDisk && basePath != null && resolvedPartitionDir != null) {
            textDataStore = new TextDataStore(StorageLayout.textDat(resolvedPartitionDir));
            bm25Index = new MemoryBM25Index(1);
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
            bm25Index = new MemoryBM25Index(1);
            textDataStore = null;
            activePartitionIndex = 0;
        }

        // ── SPLADE Index (auto-created when provider is configured) ──
        com.spectrayan.spector.memory.cortex.MemorySpladeIndex memorySpladeIndex = null;
        if (builder.sparseEncodingProvider != null) {
            memorySpladeIndex = new com.spectrayan.spector.memory.cortex.MemorySpladeIndex(1);
            log.info("SPLADE index enabled: provider={}", builder.sparseEncodingProvider.modelName());
        }

        // ── ColBERT Reranker (auto-created when provider is configured) ──
        ColBERTReranker colbertReranker = null;
        if (builder.tokenEmbeddingProvider != null) {
            ColBERTTokenCache tokenCache = new ColBERTTokenCache(
                    builder.tokenEmbeddingProvider.tokenDimensions(), 10_000);
            colbertReranker = new ColBERTReranker(builder.tokenEmbeddingProvider, tokenCache);
            log.info("ColBERT reranker enabled: provider={}, tokenDims={}",
                    builder.tokenEmbeddingProvider.modelName(),
                    builder.tokenEmbeddingProvider.tokenDimensions());
        }

        // ── Ingestion Target ──
        this.cognitiveTarget = new CognitiveIngestionTarget(
                quantizer, surpriseDetector, flashbulbPolicy,
                tierRouter, index, wal, workingStore, builder.icnuWeights,
                builder.semanticIndex, builder.tagExtractor, true,
                hebbianGraph, temporalChain, entityExtractor, entityGraph,
                bm25Index, textDataStore, activePartitionIndex,
                memorySpladeIndex, builder.sparseEncodingProvider);

        // ── Partition Manager ──
        if (isDisk) {
            this.partitionManager = new PartitionManager(
                    basePath, quantizedVecBytes, builder.semanticCapacity,
                    builder.episodicPartitionCapacity, builder.proceduralCapacity,
                    tierRouter, resolvedPartitionDir,
                    index, hebbianGraph, temporalChain, cognitiveTarget);
            cognitiveTarget.setPartitionRollCallback(partitionManager::rollPartition);
        } else {
            this.partitionManager = new PartitionManager(
                    null, quantizedVecBytes, builder.semanticCapacity,
                    builder.episodicPartitionCapacity, builder.proceduralCapacity,
                    tierRouter, null,
                    index, hebbianGraph, temporalChain, cognitiveTarget);
        }

        // ── Semantic Recall Strategy + HNSW Rebuild ──
        SemanticRecallStrategy semanticStrategy = null;
        if (builder.semanticIndex != null && tierRouter.semantic() != null) {
            semanticStrategy = new SemanticRecallStrategy(builder.semanticIndex, tierRouter.semantic(), index);
            rebuildHnswIfNeeded(builder, tierRouter);
        }

        // ── Recall Pipeline ──
        this.recallPipeline = new RecallPipeline(
                embeddingProvider, tierRouter, index,
                suppressionSet, habituationPenalty, prospectiveScheduler, wal,
                quantizer.mins(), quantizer.scales(), semanticStrategy,
                null, hebbianGraph, temporalChain, entityGraph, entityExtractor,
                builder.graphScoringPolicy, bm25Index,
                memorySpladeIndex, builder.sparseEncodingProvider, colbertReranker);

        recallPipeline.addListener(new LtpReconsolidationListener(index, tierRouter, wal));
        recallPipeline.addListener(new HebbianCoActivationListener(coActivationTracker));

        // ── Extracted Components ──
        this.importanceEstimator = new ImportanceEstimator(
                surpriseDetector, flashbulbPolicy, icnuWeights, quantizer);

        this.reflectionOrchestrator = new ReflectionOrchestrator(
                reflectDaemon, hebbianGraph, temporalChain, entityGraph,
                wal, builder.temporalRetentionDays);

        this.reinforcementHandler = new ReinforcementHandler(
                valenceTracker, hebbianGraph, lateralEvaluator, recallPipeline,
                wal, builder.twoFactorConfig);

        log.info("SpectorMemory initialized: dimensions={}, model={}, persistence={}, mode={}, " +
                 "partition={}, quantizer={}, idStrategy={}",
                dimensions, embeddingProvider.modelName(),
                basePath != null ? basePath : "in-memory",
                persistenceMode,
                resolvedPartitionDir != null ? resolvedPartitionDir.getFileName() : "none",
                builder.quantizer != null ? "user-provided" : "identity-default",
                builder.idGenerator != null ? "custom" : builder.idStrategy.name());

        // ── ID Generator ── (must be after logging)
        this.idGenerator = builder.idGenerator != null
                ? builder.idGenerator
                : builder.idStrategy.createGenerator();

        // ── Daemon Supervisor + Checkpoint Daemon ── (DISK mode only)
        if (isDisk && basePath != null && builder.checkpointIntervalSeconds > 0) {
            Path indexSavePath = resolvedPartitionDir != null
                    ? StorageLayout.indexMidx(resolvedPartitionDir)
                    : basePath.resolve(StorageLayout.LEGACY_FILE_INDEX);
            this.checkpointDaemon = new CheckpointDaemon(
                    tierRouter, wal,
                    StorageLayout.checkpointMeta(basePath),
                    index, indexSavePath,
                    hebbianGraph, temporalChain, entityGraph, coActivationTracker,
                    resolvedPartitionDir, basePath);
            this.daemonSupervisor = new DaemonSupervisor("memory");
            this.daemonSupervisor.schedule(
                    "checkpoint",
                    checkpointDaemon::checkpoint,
                    java.time.Duration.ofSeconds(builder.checkpointIntervalSeconds),
                    DaemonPolicy.CRITICAL);
        } else {
            this.checkpointDaemon = null;
            this.daemonSupervisor = null;
        }

        // ── JVM Shutdown Hook ── (DISK mode only)
        // Guarantees a final flush of all cognitive graphs and subsystems
        // when the JVM exits, even if the caller never calls close().
        if (isDisk && basePath != null) {
            this.shutdownHook = new Thread(() -> {
                if (closed.compareAndSet(false, true)) {
                    log.info("JVM shutdown hook: flushing SpectorMemory...");
                    doClose();
                    log.info("JVM shutdown hook: SpectorMemory flushed successfully");
                }
            }, "spector-memory-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } else {
            this.shutdownHook = null;
        }

        // ── Multimodal Attachment Processor ──
        if (!builder.sensoryExtractors.isEmpty()) {
            this.attachmentProcessor = new com.spectrayan.spector.memory.pipeline.AttachmentProcessor(
                    builder.sensoryExtractors, builder.assetStore);
            log.info("AttachmentProcessor initialized with {} extractors", builder.sensoryExtractors.size());
        } else {
            this.attachmentProcessor = null;
        }
    }

    /**
     * Rebuilds HNSW index from persisted semantic store if it has data but HNSW is empty.
     */
    private void rebuildHnswIfNeeded(Builder builder, TierRouter tierRouter) {
        var semStore = tierRouter.semantic();
        int storeSize = semStore.size();
        if (storeSize > 0 && builder.semanticIndex.size() == 0) {
            log.info("Rebuilding HNSW index from {} persisted semantic records...", storeSize);
            long startMs = System.currentTimeMillis();
            var seg = semStore.primarySegment();
            var recLayout = semStore.layout();
            int stride = recLayout.stride();
            int vecBytes = recLayout.quantizedVecBytes();
            long baseOffset = semStore.filePath() != null
                    ? com.spectrayan.spector.memory.cortex.AbstractTierStore.METADATA_HEADER_BYTES : 0;

            int rebuilt = 0;
            for (int i = 0; i < storeSize; i++) {
                long recordOff = baseOffset + (long) i * stride;
                byte[] quantized = new byte[vecBytes];
                java.lang.foreign.MemorySegment.copy(
                        seg, java.lang.foreign.ValueLayout.JAVA_BYTE,
                        recLayout.vectorOffset(recordOff),
                        java.lang.foreign.MemorySegment.ofArray(quantized),
                        java.lang.foreign.ValueLayout.JAVA_BYTE, 0, vecBytes);

                float[] vector = quantizer.decode(quantized);
                String id = index.findIdByOffset(MemoryType.SEMANTIC, recordOff);
                if (id != null && !builder.semanticIndex.isReadOnly()) {
                    builder.semanticIndex.add(id, i, vector);
                    rebuilt++;
                }
            }
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("HNSW rebuild complete: {}/{} vectors added in {}ms", rebuilt, storeSize, elapsed);
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
        return remember(id, text, type, source,
                (com.spectrayan.spector.memory.neurodivergent.IngestionHints) null, tags);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source,
                                              com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                              String... tags) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (shouldChunk(text)) {
                    rememberChunked(id, text, type, source, hints, null, tags);
                } else {
                    float[] vector = embeddingProvider.embed(text).vector();
                    cognitiveTarget.ingestCognitive(id, text, vector, type, tags, source, hints);
                }
                checkCircadianTrigger(type);
            } catch (RuntimeException e) {
                log.error("Failed to remember '{}': {}", id, e.getMessage(), e);
                throw new SpectorServerException(ErrorCode.INGESTION_PIPELINE_FAILED, e, id);
            }
        }, ConcurrentTasks.virtualExecutor());
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              String... tags) {
        return remember(id, text, type, MemorySource.OBSERVED, tags);
    }

    @Override
    public CompletableFuture<String> remember(String text, MemoryType type,
                                               MemorySource source, String... tags) {
        String generatedId = idGenerator.generate();
        return remember(generatedId, text, type, source, tags)
                .thenApply(v -> generatedId);
    }

    @Override
    public CompletableFuture<String> remember(String text, MemoryType type,
                                               MemorySource source,
                                               com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                               String... tags) {
        String generatedId = idGenerator.generate();
        return remember(generatedId, text, type, source, hints, tags)
                .thenApply(v -> generatedId);
    }

    @Override
    public CompletableFuture<String> remember(String text, MemoryType type,
                                               MemorySource source,
                                               IngestionContext context,
                                               String... tags) {
        String generatedId = idGenerator.generate();
        return remember(generatedId, text, type, source, context, tags)
                .thenApply(v -> generatedId);
    }

    @Override
    public CompletableFuture<Void> remember(String id, String text, MemoryType type,
                                              MemorySource source,
                                              IngestionContext context,
                                              String... tags) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (shouldChunk(text)) {
                    rememberChunked(id, text, type, source, null, context, tags);
                } else {
                    float[] vector = embeddingProvider.embed(text).vector();
                    cognitiveTarget.ingestCognitive(id, text, vector, type, tags, source, context);
                }

                // Process attachments if present in context metadata
                if (context != null && context.hasAttachments()) {
                    processAttachments(id, context, type, source, tags);
                }

                checkCircadianTrigger(type);
            } catch (RuntimeException e) {
                log.error("Failed to remember '{}': {}", id, e.getMessage(), e);
                throw new SpectorServerException(ErrorCode.INGESTION_PIPELINE_FAILED, e, id);
            }
        }, ConcurrentTasks.virtualExecutor());
    }

    /**
     * Checks if episodic ingestion volume has reached the circadian trigger threshold.
     * If so, fires a background reflection cycle.
     */
    private void checkCircadianTrigger(MemoryType type) {
        if (type == MemoryType.EPISODIC) {
            int count = episodicIngestCount.incrementAndGet();
            if (count >= circadianPolicy.volumeTrigger()) {
                episodicIngestCount.set(0);
                ConcurrentTasks.fireAndForget(() -> {
                    log.info("Circadian volume trigger: {} episodic memories → auto-reflect", count);
                    reflect();
                });
            }
        }
    }

    /**
     * Returns true if the text should be chunked before ingestion.
     */
    private boolean shouldChunk(String text) {
        return chunker != null && text != null && text.length() > chunker.chunkSize();
    }

    /**
     * Chunks text, parallel-embeds all chunks, and ingests each with the caller's
     * cognitive metadata. The parent memory ID is added as a tag to all chunks.
     *
     * <p>If the caller provided tags, those tags are applied to every chunk.
     * Each chunk ID follows the convention {@code parentId::chunk-N}.</p>
     *
     * @param id      parent memory ID
     * @param text    full text to chunk
     * @param type    memory tier
     * @param source  memory source
     * @param hints   ICNU hints (nullable — used when context is null)
     * @param context ingestion context (nullable — used when hints is null)
     * @param tags    caller-provided tags to apply to all chunks
     */
    private void rememberChunked(String id, String text, MemoryType type,
                                  MemorySource source,
                                  com.spectrayan.spector.memory.neurodivergent.IngestionHints hints,
                                  IngestionContext context,
                                  String... tags) {
        var chunks = chunker.chunk(id, text);
        if (chunks.isEmpty()) {
            log.warn("[Remember] Chunker returned empty for '{}' ({} chars), skipping", id, text.length());
            return;
        }

        // Parallel-embed all chunks
        List<String> chunkTexts = chunks.stream().map(TextChunker.Chunk::text).toList();
        List<PipelineEmbeddingResult> embeddings = parallelPipeline.embed(chunkTexts, EmbedConfig.DEFAULT);

        // Build per-chunk tags: caller tags + parent ID tag
        String parentTag = sanitizeTag(id);
        String[] chunkTags;
        if (tags != null && tags.length > 0) {
            chunkTags = new String[tags.length + 1];
            System.arraycopy(tags, 0, chunkTags, 0, tags.length);
            chunkTags[tags.length] = parentTag;
        } else {
            chunkTags = new String[]{ parentTag };
        }

        int stored = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            var embedding = embeddings.get(i);

            if (!embedding.success()) {
                failures.add(chunk.chunkId());
                log.warn("[Remember] Embedding failed for chunk '{}': {}", chunk.chunkId(), embedding.error());
                continue;
            }

            try {
                if (context != null) {
                    cognitiveTarget.ingestCognitive(chunk.chunkId(), chunk.text(),
                            embedding.embedding(), type, chunkTags, source, context);
                } else {
                    cognitiveTarget.ingestCognitive(chunk.chunkId(), chunk.text(),
                            embedding.embedding(), type, chunkTags, source, hints);
                }
                stored++;
            } catch (RuntimeException e) {
                failures.add(chunk.chunkId());
                log.warn("[Remember] Ingestion failed for chunk '{}': {}", chunk.chunkId(), e.getMessage());
            }
        }

        log.info("[Remember] Chunked '{}' → {} chunks stored ({} failed) from {} chars",
                id.length() > 60 ? "..." + id.substring(id.length() - 57) : id,
                stored, failures.size(), text.length());
    }

    /** Sanitizes a memory ID into a valid tag (lowercase, hyphens, no special chars). */
    private static String sanitizeTag(String id) {
        if (id == null) return "unknown";
        return id.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9\\-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * Processes attachments from IngestionContext and creates sub-memories.
     *
     * <p>Each extracted chunk from an attachment is stored as a separate memory
     * with a Hebbian edge linking it to the parent memory.</p>
     */
    private void processAttachments(String parentId, IngestionContext context,
                                     MemoryType type, MemorySource source, String[] tags) {
        if (attachmentProcessor == null) {
            log.debug("No AttachmentProcessor configured — skipping attachments for '{}'", parentId);
            return;
        }

        List<com.spectrayan.spector.memory.pipeline.AttachmentProcessor.AttachmentResult> results =
                attachmentProcessor.processAttachments(parentId, context);

        if (results.isEmpty()) {
            log.debug("No attachment chunks produced for '{}'", parentId);
            return;
        }

        int ingested = 0;
        for (var result : results) {
            try {
                // Build sub-memory context with Hebbian edge to parent
                var subContext = IngestionContext.builder()
                        .metadata(result.metadata())
                        .hebbianEdge(parentId, 0.8f)  // strong link to parent
                        .build();

                float[] vector = embeddingProvider.embed(result.text()).vector();
                cognitiveTarget.ingestCognitive(
                        result.chunkId(), result.text(), vector, type, tags, source, subContext);
                ingested++;
            } catch (RuntimeException e) {
                log.warn("[Attachment] Failed to ingest chunk '{}': {}", result.chunkId(), e.getMessage());
            }
        }

        log.info("[Attachment] Processed {} attachment chunks for parent '{}' ({} ingested)",
                results.size(), parentId, ingested);
    }

    @Override
    public ImportanceEstimate estimateImportance(String text,
                                                  com.spectrayan.spector.memory.neurodivergent.IngestionHints hints) {
        return importanceEstimator.estimate(text, hints, embeddingProvider,
                partitionManager.tierRouter(), index);
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
        MemorySegment segment = partitionManager.tierRouter().segmentFor(loc.type());
        if (segment != null) {
            CognitiveRecordLayout layout = partitionManager.tierRouter().layoutFor(loc.type());
            layout.tombstone(segment, loc.offset());
        }
        wal.appendForget(id);
        index.remove(id);
        log.debug("Forget: '{}' tombstoned", id);
    }

    @Override
    public ReflectReport reflect() {
        return reflectionOrchestrator.reflect(partitionManager.tierRouter(), index);
    }

    // ══════════════════════════════════════════════════════════════
    // EXTENDED API — reinforce / suppress / introspect
    // ══════════════════════════════════════════════════════════════

    @Override
    public void reinforce(String memoryId, byte valence) {
        reinforcementHandler.reinforce(memoryId, valence,
                partitionManager.tierRouter(), index);
    }

    @Override
    public void reinforce(String memoryId, byte valence,
                           com.spectrayan.spector.memory.neurodivergent.IngestionHints updatedHints) {
        reinforcementHandler.reinforceWithHints(memoryId, valence, updatedHints,
                partitionManager.tierRouter(), index);
    }

    @Override
    public void suppress(String memoryId, String reason) {
        suppressionSet.suppress(memoryId, reason);
        MemoryLocation loc = index.locate(memoryId);
        if (loc != null) {
            suppressionSet.registerOffset(loc.type().ordinal(), loc.offset());
        }
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
        partitionManager.tierRouter().layoutFor(loc.type())
                .markResolved(partitionManager.tierRouter().segmentFor(loc.type()), loc.offset());
        log.debug("Zeigarnik: marked '{}' as RESOLVED", memoryId);
    }

    @Override
    public void markUnresolved(String memoryId) {
        var loc = index.locate(memoryId);
        if (loc == null) return;
        partitionManager.tierRouter().layoutFor(loc.type())
                .markUnresolved(partitionManager.tierRouter().segmentFor(loc.type()), loc.offset());
        log.debug("Zeigarnik: marked '{}' as UNRESOLVED", memoryId);
    }

    @Override
    public MemoryInsight introspect(String topic) {
        List<CognitiveResult> results = recall(topic, RecallOptions.builder().topK(20).build());
        return introspector.analyze(topic, results);
    }

    @Override
    public WhyNotExplanation whyNot(String memoryId, String queryText, RecallOptions options) {
        var loc = index.locate(memoryId);
        if (loc == null) {
            return new WhyNotExplanation(memoryId, queryText, false, false,
                    null, 0f, WhyNotExplanation.Reason.NOT_FOUND,
                    "Memory '" + memoryId + "' does not exist in the index.");
        }

        var layout = partitionManager.tierRouter().layoutFor(loc.type());
        var segment = partitionManager.tierRouter().segmentFor(loc.type());
        if (layout != null && segment != null) {
            byte flags = segment.get(SynapticHeaderConstants.LAYOUT_FLAGS,
                    loc.offset() + SynapticHeaderConstants.OFFSET_FLAGS);
            if (SynapticHeaderConstants.isTombstoned(flags)) {
                return new WhyNotExplanation(memoryId, queryText, true, false,
                        null, 0f, WhyNotExplanation.Reason.TOMBSTONED,
                        "Memory '" + memoryId + "' has been deleted (tombstone flag set).");
            }
        }

        if (suppressionSet.isSuppressed(memoryId)) {
            return new WhyNotExplanation(memoryId, queryText, true, true,
                    null, 0f, WhyNotExplanation.Reason.SUPPRESSED,
                    "Memory '" + memoryId + "' is in the suppression set. "
                    + "Use unsuppress(\"" + memoryId + "\") to allow recall.");
        }

        int originalTopK = options != null ? options.topK() : 5;
        RecallOptions observeOptions = RecallOptions.builder()
                .recallMode(RecallMode.OBSERVE)
                .topK(Math.max(originalTopK, 20))
                .build();

        List<CognitiveResult> results = recall(queryText, observeOptions);

        CognitiveResult found = null;
        for (CognitiveResult r : results) {
            if (memoryId.equals(r.id())) {
                found = r;
                break;
            }
        }

        if (found != null) {
            return new WhyNotExplanation(memoryId, queryText, true, false,
                    found.breakdown(), 0f, WhyNotExplanation.Reason.OUTRANKED,
                    "Memory '" + memoryId + "' WAS found in extended recall "
                    + "(score=" + String.format("%.4f", found.score()) + "). "
                    + "It may have been outside your original topK cutoff.");
        }

        float cutoffScore = results.isEmpty() ? 0f : results.get(results.size() - 1).score();
        return new WhyNotExplanation(memoryId, queryText, true, false,
                null, cutoffScore, WhyNotExplanation.Reason.FILTERED,
                "Memory '" + memoryId + "' was eliminated by pre-filters "
                + "(tag gate, valence range, importance floor, or time decay). "
                + "TopK cutoff score: " + String.format("%.4f", cutoffScore) + ". "
                + (cutoffScore > 0 ? "Check if tags/valence/importance match your query options." : ""));
    }

    // ══════════════════════════════════════════════════════════════
    // INSPECT — Full Cognitive X-Ray
    // ══════════════════════════════════════════════════════════════

    @Override
    public CognitiveRecord inspect(String id) {
        if (id == null) return null;

        MemoryLocation loc = index.locate(id);
        if (loc == null) return null;

        String text = index.text(id);
        MemorySource source = index.source(id);
        String[] memTags = index.tags(id);

        MemorySegment segment = partitionManager.tierRouter().segmentFor(loc.type());
        CognitiveRecordLayout layout = partitionManager.tierRouter().layoutFor(loc.type());

        if (segment == null || layout == null) return null;

        // Read the 64-byte cognitive header
        var header = layout.readHeader(segment, loc.offset());

        // Read the quantized vector payload
        int vecBytes = layout.quantizedVecBytes();
        byte[] quantizedVec = new byte[vecBytes];
        long vecOffset = layout.vectorOffset(loc.offset());
        MemorySegment.copy(
                segment, java.lang.foreign.ValueLayout.JAVA_BYTE, vecOffset,
                MemorySegment.ofArray(quantizedVec),
                java.lang.foreign.ValueLayout.JAVA_BYTE, 0, vecBytes);

        // Read extended fields that aren't in the base CognitiveHeader
        int spectorRecallCount = layout.readSpectorRecallCount(segment, loc.offset());

        return new CognitiveRecord(
                id, text, loc.type(), source, memTags,
                header.timestampMs(), header.synapticTags(), header.exactNorm(),
                header.importance(), header.agentRecallCount(), spectorRecallCount,
                header.centroidId(), header.valence(), header.arousal(),
                header.storageStrength(), header.flags(),
                quantizedVec, loc.partitionIndex(), loc.offset());
    }

    // ══════════════════════════════════════════════════════════════
    // BROWSE — Tag-Based Iteration
    // ══════════════════════════════════════════════════════════════

    @Override
    public List<CognitiveRecord> browse(String... tags) {
        if (tags == null || tags.length == 0) return List.of();

        // Pre-compute query tags as a lowercase Set (O(1) lookup)
        var queryTagSet = new java.util.HashSet<String>(tags.length);
        for (String tag : tags) queryTagSet.add(tag.toLowerCase());

        var results = new java.util.ArrayList<CognitiveRecord>();

        for (var entry : index.locationMap().entrySet()) {
            String memId = entry.getKey();
            String[] memTags = index.tags(memId);
            if (memTags.length < tags.length) continue; // fast-path: can't match if fewer tags

            // AND semantics: memory must contain all requested tags
            var memTagSet = new java.util.HashSet<String>(memTags.length);
            for (String memTag : memTags) memTagSet.add(memTag.toLowerCase());

            if (memTagSet.containsAll(queryTagSet)) {
                MemoryLocation loc = entry.getValue();
                MemorySegment segment = partitionManager.tierRouter().segmentFor(loc.type());
                CognitiveRecordLayout layout = partitionManager.tierRouter().layoutFor(loc.type());

                if (segment != null && layout != null) {
                    var header = layout.readHeader(segment, loc.offset());
                    if (!SynapticHeaderConstants.isTombstoned(header.flags())) {
                        int spectorRecallCount = layout.readSpectorRecallCount(segment, loc.offset());
                        results.add(new CognitiveRecord(
                                memId, index.text(memId), loc.type(),
                                index.source(memId), memTags,
                                header.timestampMs(), header.synapticTags(), header.exactNorm(),
                                header.importance(), header.agentRecallCount(), spectorRecallCount,
                                header.centroidId(), header.valence(), header.arousal(),
                                header.storageStrength(), header.flags(),
                                null, // no vector for browse (use inspect for full detail)
                                loc.partitionIndex(), loc.offset()));
                    }
                }
            }
        }

        return List.copyOf(results);
    }

    // ══════════════════════════════════════════════════════════════
    // EXPORT — Bulk Memory Export
    // ══════════════════════════════════════════════════════════════

    @Override
    public String exportJson() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var arrayNode = mapper.createArrayNode();

        for (var entry : index.locationMap().entrySet()) {
            String memId = entry.getKey();
            CognitiveRecord record = inspect(memId);
            if (record != null && !record.isTombstoned()) {
                // Parse record's JSON into a node to avoid double-encoding
                arrayNode.add(mapper.readTree(record.toJson()));
            }
        }

        return arrayNode.toString();
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
    public int totalMemories() { return partitionManager.tierRouter().totalCount(); }

    @Override
    public int memoryCount(MemoryType type) { return partitionManager.tierRouter().countFor(type); }

    @Override
    public int decay(Duration olderThan, float factor) {
        if (olderThan == null) { throw new SpectorValidationException(ErrorCode.ARGUMENT_NULL, "olderThan"); }
        if (factor < 0f || factor > 1f) throw new SpectorValidationException(ErrorCode.ARGUMENT_OUT_OF_RANGE, "factor", 0, 1, 0);

        long nowMs = System.currentTimeMillis();
        long thresholdMs = nowMs - olderThan.toMillis();

        var partitions = partitionManager.tierRouter().episodic().partitions();
        if (partitions.isEmpty()) return 0;

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
    // ADMIN INTERFACE
    // ══════════════════════════════════════════════════════════════

    @Override public SpectorMemoryAdmin admin() { return this; }

    // ══════════════════════════════════════════════════════════════
    // SUBSYSTEM ACCESSORS (implements both SpectorMemory + SpectorMemoryAdmin)
    // ══════════════════════════════════════════════════════════════

    @Override public CoActivationTracker coActivation() { return coActivationTracker; }
    @Override public MemoryWal wal() { return wal; }
    @Override public ProspectiveScheduler prospective() { return prospectiveScheduler; }
    @Override public SuppressionSet suppression() { return suppressionSet; }
    @Override public HabituationPenalty habituation() { return habituationPenalty; }
    @Override public ScalarQuantizer quantizer() { return quantizer; }
    @Override public CognitiveIngestionTarget cognitiveTarget() { return cognitiveTarget; }
    @Override public RecallPipeline recallPipeline() { return recallPipeline; }
    @Override public TierRouter tierRouter() { return partitionManager.tierRouter(); }
    @Override public MemoryIndex index() { return index; }
    @Override public LateralEvaluator lateralEvaluator() { return lateralEvaluator; }
    @Override public HebbianGraph hebbianGraph() { return hebbianGraph; }
    @Override public TemporalChain temporalChain() { return temporalChain; }
    @Override public EntityGraph entityGraph() { return entityGraph; }

    /** Returns the namespace manager (null if IN_MEMORY mode). */
    public SpectorNamespaceManager namespaceManager() { return namespaceManager; }

    // ══════════════════════════════════════════════════════════════
    // VACUUM / COMPACTION
    // ══════════════════════════════════════════════════════════════

    private final ReentrantLock vacuumLock = new ReentrantLock();

    @Override
    public CompactionResult vacuum(MemoryType tier) {
        TierRouter router = partitionManager.tierRouter();
        com.spectrayan.spector.memory.cortex.TierStore store = router.get(tier);
        if (!(store instanceof com.spectrayan.spector.memory.cortex.AbstractTierStore ats)) {
            log.warn("Vacuum: tier {} is not compactable", tier);
            return null;
        }
        vacuumLock.lock();
        try {
            return VacuumCompactor.compact(ats, tier, index);
        } finally {
            vacuumLock.unlock();
        }
    }

    @Override
    public java.util.Map<MemoryType, Float> tombstoneRatios() {
        TierRouter router = partitionManager.tierRouter();
        java.util.Map<MemoryType, Float> ratios = new java.util.EnumMap<>(MemoryType.class);
        for (MemoryType type : MemoryType.values()) {
            com.spectrayan.spector.memory.cortex.TierStore store = router.get(type);
            if (store instanceof com.spectrayan.spector.memory.cortex.AbstractTierStore ats) {
                ratios.put(type, ats.tombstoneRatio());
            }
        }
        return ratios;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            log.debug("SpectorMemory.close() already called, skipping");
            return;
        }

        // Deregister shutdown hook to avoid double-flush
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down — hook is running or already ran
            }
        }

        doClose();
    }

    /**
     * Internal close logic — called by both {@link #close()} and the JVM shutdown hook.
     * Guarded by the {@code closed} AtomicBoolean so it runs at most once.
     */
    private void doClose() {
        log.info("SpectorMemory closing ({} total memories, mode={})",
                totalMemories(), persistenceMode);

        // Stop daemon supervisor (stops all managed daemons)
        if (daemonSupervisor != null) {
            daemonSupervisor.close();
        }

        // Final checkpoint flush before closing storage
        if (checkpointDaemon != null) {
            try {
                checkpointDaemon.checkpoint();
            } catch (Exception e) {
                log.warn("Final checkpoint on close failed: {}", e.getMessage());
            }
        }

        PersistenceManager.flushAndClose(
                persistenceMode, persistencePath,
                partitionManager.activePartitionDir(),
                index, hebbianGraph, temporalChain, entityGraph,
                coActivationTracker, partitionManager.tierRouter(), wal);
    }

    // ══════════════════════════════════════════════════════════════
    // BUILDER
    // ══════════════════════════════════════════════════════════════

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        int dimensions;
        EmbeddingProvider embeddingProvider;
        Path persistencePath;
        MemoryPersistenceMode persistenceMode = MemoryPersistenceMode.DISK;
        boolean persistWorkingMemory = false;
        CircadianPolicy circadianPolicy = CircadianPolicy.DEFAULT;
        int workingCapacity = 100;
        int episodicPartitionCapacity = 1_000;
        int semanticCapacity = 10_000;
        int nodesPerPartition = 10_000;
        int proceduralCapacity = 1_000;
        int surpriseWarmup = 20;
        double flashbulbThreshold = 3.0;
        float valenceLearningRate = 0.3f;
        float deduplicationRadius = 0.05f;
        TextGenerationProvider textGenerationProvider;
        ScalarQuantizer quantizer;
        com.spectrayan.spector.index.VectorIndex semanticIndex;
        long inhibitionTtlMs = 300_000L;
        float inhibitionFloor = 0.1f;
        IcnuWeights icnuWeights;
        boolean pinSourceEpisodes = false;
        int pinnedQuota = 10_000;
        com.spectrayan.spector.memory.pipeline.TagExtractor tagExtractor;
        CognitiveProfileConfig profileConfig = CognitiveProfileConfig.allEnabled();

        // 3-Layer Cognitive Graph configuration
        int hebbianGraphCapacity = 0;
        int temporalChainCapacity = 0;
        EntityExtractionMode entityExtractionMode = EntityExtractionMode.NONE;
        EntityExtractor entityExtractor;
        int entityGraphCapacity = 50_000;
        int maxEntitiesPerMemory = 10;
        int maxRelationsPerMemory = 20;
        com.spectrayan.spector.embed.GenerationOptions llmGenerationOptions;
        GraphScoringPolicy graphScoringPolicy = GraphScoringPolicy.DEFAULT;
        int temporalRetentionDays = 7;
        com.spectrayan.spector.memory.synapse.TwoFactorConfig twoFactorConfig
                = com.spectrayan.spector.memory.synapse.TwoFactorConfig.DEFAULT;

        // ID generation strategy
        IdStrategy idStrategy = IdStrategy.TSID;
        MemoryIdGenerator idGenerator;

        // SPLADE + ColBERT providers
        SparseEncodingProvider sparseEncodingProvider;
        TokenEmbeddingProvider tokenEmbeddingProvider;

        // Checkpoint daemon configuration
        int checkpointIntervalSeconds = 30;

        // Chunking for remember() — default enabled with standard chunker
        TextChunker chunker = new TextChunker();

        // Multimodal attachment processing
        java.util.List<com.spectrayan.spector.ingestion.sensory.SensoryExtractor> sensoryExtractors = java.util.List.of();
        com.spectrayan.spector.ingestion.sensory.AssetStore assetStore;

        public Builder dimensions(int dimensions) { this.dimensions = dimensions; return this; }
        public Builder embeddingProvider(EmbeddingProvider p) { this.embeddingProvider = p; return this; }
        public Builder persistence(Path p) { this.persistencePath = p; return this; }
        /** Sets the persistence mode (default: {@link MemoryPersistenceMode#DISK}). */
        public Builder persistenceMode(MemoryPersistenceMode mode) { this.persistenceMode = mode; return this; }
        /** If true, Working memory is also persisted to disk in DISK mode (default: false). */
        public Builder persistWorkingMemory(boolean persist) { this.persistWorkingMemory = persist; return this; }
        public Builder reflectPolicy(CircadianPolicy p) { this.circadianPolicy = p; return this; }

        /** Sets the text chunker for remember() auto-chunking (default: TextChunker(512, 64)). */
        public Builder chunker(TextChunker chunker) { this.chunker = chunker; return this; }

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

        /** LLM generation options for entity extraction (temperature, maxTokens, topP). */
        public Builder llmGenerationOptions(com.spectrayan.spector.embed.GenerationOptions opts) { this.llmGenerationOptions = opts; return this; }

        /** Graph scoring policy — configurable weights for cognitive graph steps (default: GraphScoringPolicy.DEFAULT). */
        public Builder graphScoringPolicy(GraphScoringPolicy policy) { this.graphScoringPolicy = policy; return this; }

        /** Temporal chain retention in days — links older than this are pruned during reflect() (default: 7). */
        public Builder temporalRetentionDays(int days) { this.temporalRetentionDays = days; return this; }

        /** Checkpoint interval in seconds (default: 30). Set to 0 to disable automatic checkpointing. */
        public Builder checkpointIntervalSeconds(int seconds) { this.checkpointIntervalSeconds = seconds; return this; }

        /** Two-Factor Memory (Bjork & Bjork) configuration (default: TwoFactorConfig.DEFAULT). */
        public Builder twoFactorConfig(com.spectrayan.spector.memory.synapse.TwoFactorConfig config) { this.twoFactorConfig = config; return this; }

        /**
         * Parses a cognitive profile config from a YAML string value.
         * Supports: "ALL", "CORE_ONLY", "WITH_NEURODIVERGENT", or comma-separated profile names.
         * @see CognitiveProfileConfig#fromConfigValue(String)
         */
        public Builder cognitiveProfiles(String configValue) { this.profileConfig = CognitiveProfileConfig.fromConfigValue(configValue); return this; }

        // ── ID Generation ──

        /**
         * Sets the ID generation strategy for auto-generated memory IDs.
         *
         * <p>Default: {@link IdStrategy#TSID} — 13-char time-sorted, distributed-safe.
         * This is only used when {@link SpectorMemory#remember(String, MemoryType, MemorySource, String...)}
         * is called without an explicit ID.</p>
         *
         * @param strategy the built-in strategy to use
         * @return this builder
         */
        public Builder idStrategy(IdStrategy strategy) { this.idStrategy = strategy; return this; }

        /**
         * Sets a custom ID generator, overriding the built-in {@link #idStrategy(IdStrategy)}.
         *
         * <p>Use this for custom ID schemes (e.g., database-sequence-backed, ULID, etc.).
         * The generator must be thread-safe.</p>
         *
         * @param generator the custom generator
         * @return this builder
         */
        public Builder idGenerator(MemoryIdGenerator generator) { this.idGenerator = generator; return this; }

        /**
         * Sets the sparse encoding provider for SPLADE retrieval.
         *
         * <p>When provided, a {@code MemorySpladeIndex} is automatically created and wired
         * into both the ingestion and recall pipelines, enabling SPLADE, SPLADE_HYBRID,
         * and FULL_STACK text search modes.</p>
         *
         * @param provider the sparse encoding provider (e.g., OllamaSparseEncodingProvider)
         * @return this builder
         */
        public Builder sparseEncodingProvider(SparseEncodingProvider provider) { this.sparseEncodingProvider = provider; return this; }

        /**
         * Sets the token embedding provider for ColBERT reranking.
         *
         * <p>When provided, a {@code ColBERTReranker} with a {@code ColBERTTokenCache}
         * is automatically created and wired into the recall pipeline, enabling
         * COLBERT_RERANK and FULL_STACK text search modes.</p>
         *
         * @param provider the token embedding provider (e.g., OllamaTokenEmbeddingProvider)
         * @return this builder
         */
        public Builder tokenEmbeddingProvider(TokenEmbeddingProvider provider) { this.tokenEmbeddingProvider = provider; return this; }

        /** Registers sensory extractors for multimodal attachment processing. */
        public Builder sensoryExtractors(java.util.List<com.spectrayan.spector.ingestion.sensory.SensoryExtractor> extractors) {
            this.sensoryExtractors = extractors != null ? extractors : java.util.List.of();
            return this;
        }

        /** Sets the asset store for persisting original attachment files. */
        public Builder assetStore(com.spectrayan.spector.ingestion.sensory.AssetStore store) {
            this.assetStore = store;
            return this;
        }

        public SpectorMemory build() {
            if (dimensions <= 0 && embeddingProvider != null) {
                dimensions = embeddingProvider.dimensions();
            }
            return new DefaultSpectorMemory(this);
        }
    }
}
