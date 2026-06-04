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
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.StorageLayout;
import com.spectrayan.spector.memory.hebbian.HebbianGraph;
import com.spectrayan.spector.memory.hebbian.HebbianGraph.HebbianEdge;
import com.spectrayan.spector.memory.temporal.TemporalChain;
import com.spectrayan.spector.memory.graph.EntityGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages per-partition graph instances (Hebbian, Temporal, Entity)
 * with cross-partition spreading activation support.
 *
 * <h3>Architecture</h3>
 * <p>Each colocated partition contains its own graph instances, enabling
 * locality and parallel I/O during recall. The manager provides a unified
 * view for cross-partition graph queries (e.g., spreading activation that
 * crosses partition boundaries).</p>
 *
 * <h3>Partition Graph Layout</h3>
 * <pre>
 *   partition/
 *   ├── hebbian.graph   ← intra-partition edges only
 *   ├── temporal.chain  ← intra-partition sequence links
 *   └── entity.graph    ← intra-partition entity relations
 * </pre>
 *
 * <h3>Cross-Partition Edges</h3>
 * <p>Cross-partition Hebbian edges are stored in the global
 * {@code cross/hebbian-cross.graph} file, with a configurable cap
 * to prevent unbounded growth.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@link CopyOnWriteArrayList} for partition lists, matching
 * the pattern used by {@link ColocatedPartitionManager}.</p>
 *
 * @see ColocatedPartitionManager
 * @see HebbianGraph
 * @see TemporalChain
 * @see EntityGraph
 */
public final class PartitionedGraphManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PartitionedGraphManager.class);

    /** Default cross-partition Hebbian edge limit. */
    public static final int DEFAULT_CROSS_EDGE_LIMIT = 50_000;

    private final CopyOnWriteArrayList<HebbianGraph> hebbianGraphs;
    private final CopyOnWriteArrayList<TemporalChain> temporalChains;
    private final CopyOnWriteArrayList<EntityGraph> entityGraphs;

    // Cross-partition edges (separate from intra-partition)
    private final HebbianGraph crossHebbianGraph;
    private final int crossEdgeLimit;

    // Graph capacity per partition
    private final int hebbianCapPerPartition;
    private final int temporalCapPerPartition;
    private final int entityCapPerPartition;
    private final int entityEdgeCapPerPartition;

    /**
     * Creates a partitioned graph manager.
     *
     * @param hebbianCapPerPartition  Hebbian graph capacity per partition
     * @param temporalCapPerPartition temporal chain capacity per partition
     * @param entityCapPerPartition   entity graph node capacity per partition
     * @param entityEdgeCapPerPartition entity graph edge capacity per partition
     * @param crossHebbianGraph       shared cross-partition Hebbian graph (nullable)
     * @param crossEdgeLimit          max cross-partition edges
     */
    public PartitionedGraphManager(int hebbianCapPerPartition,
                                    int temporalCapPerPartition,
                                    int entityCapPerPartition,
                                    int entityEdgeCapPerPartition,
                                    HebbianGraph crossHebbianGraph,
                                    int crossEdgeLimit) {
        this.hebbianCapPerPartition = hebbianCapPerPartition;
        this.temporalCapPerPartition = temporalCapPerPartition;
        this.entityCapPerPartition = entityCapPerPartition;
        this.entityEdgeCapPerPartition = entityEdgeCapPerPartition;
        this.crossHebbianGraph = crossHebbianGraph;
        this.crossEdgeLimit = crossEdgeLimit;

        this.hebbianGraphs = new CopyOnWriteArrayList<>();
        this.temporalChains = new CopyOnWriteArrayList<>();
        this.entityGraphs = new CopyOnWriteArrayList<>();
    }

    /**
     * Loads or creates graph instances for a partition.
     *
     * @param partition the colocated partition to load/create graphs for
     */
    public void loadPartition(ColocatedPartition partition) {
        Path dir = partition.directory();

        // Hebbian: load from file or create new
        HebbianGraph hebb = HebbianGraph.load(
                StorageLayout.hebbianGraph(dir), hebbianCapPerPartition);

        // Temporal: load from file or create new
        TemporalChain temp = TemporalChain.load(
                StorageLayout.temporalChain(dir), temporalCapPerPartition);

        // Entity: load from file or create new (if entity extraction is enabled)
        EntityGraph entity;
        if (entityCapPerPartition > 0) {
            entity = EntityGraph.load(
                    StorageLayout.entityGraph(dir), entityCapPerPartition, entityEdgeCapPerPartition);
        } else {
            entity = null;
        }

        // Ensure lists are large enough
        while (hebbianGraphs.size() <= partition.seqNo()) {
            hebbianGraphs.add(null);
            temporalChains.add(null);
            entityGraphs.add(null);
        }

        hebbianGraphs.set(partition.seqNo(), hebb);
        temporalChains.set(partition.seqNo(), temp);
        if (entity != null) entityGraphs.set(partition.seqNo(), entity);

        log.debug("Loaded graphs for partition {}: hebbian={}, temporal={}, entity={}",
                partition.seqNo(),
                hebb.totalEdges(),
                temp.capacity(),
                entity != null ? "loaded" : "disabled");
    }

    /**
     * Returns the Hebbian graph for a specific partition.
     *
     * @param partitionIndex zero-based partition index
     * @return the partition's HebbianGraph, or null if not loaded
     */
    public HebbianGraph hebbian(int partitionIndex) {
        if (partitionIndex < 0 || partitionIndex >= hebbianGraphs.size()) return null;
        return hebbianGraphs.get(partitionIndex);
    }

    /**
     * Returns the temporal chain for a specific partition.
     *
     * @param partitionIndex zero-based partition index
     * @return the partition's TemporalChain, or null if not loaded
     */
    public TemporalChain temporal(int partitionIndex) {
        if (partitionIndex < 0 || partitionIndex >= temporalChains.size()) return null;
        return temporalChains.get(partitionIndex);
    }

    /**
     * Returns the entity graph for a specific partition.
     *
     * @param partitionIndex zero-based partition index
     * @return the partition's EntityGraph, or null if not loaded or disabled
     */
    public EntityGraph entity(int partitionIndex) {
        if (partitionIndex < 0 || partitionIndex >= entityGraphs.size()) return null;
        return entityGraphs.get(partitionIndex);
    }

    /**
     * Returns the cross-partition Hebbian graph (shared across all partitions).
     */
    public HebbianGraph crossHebbian() {
        return crossHebbianGraph;
    }

    /**
     * Performs cross-partition spreading activation from a node in one partition.
     *
     * <p>First activates intra-partition neighbors, then checks the cross-partition
     * Hebbian graph for edges that cross partition boundaries.</p>
     *
     * @param partitionIndex source partition
     * @param nodeIndex      source node index within the partition
     * @param depth          activation depth (1-3 recommended)
     * @return list of activated edges (may span multiple partitions)
     */
    public List<HebbianEdge> spreadingActivation(int partitionIndex, int nodeIndex, int depth) {
        List<HebbianEdge> results = new ArrayList<>();

        // Intra-partition activation
        HebbianGraph intra = hebbian(partitionIndex);
        if (intra != null) {
            results.addAll(intra.activateNeighbors(nodeIndex, depth));
        }

        // Cross-partition activation
        if (crossHebbianGraph != null) {
            List<HebbianEdge> crossEdges = crossHebbianGraph.activateNeighbors(nodeIndex, 1);
            results.addAll(crossEdges);
        }

        // Sort by descending weight
        results.sort(Comparator.comparingDouble(HebbianEdge::weight).reversed());
        return results;
    }

    /**
     * Saves all partition graphs to their respective directories.
     *
     * @param partitions the list of colocated partitions
     */
    public void saveAll(List<ColocatedPartition> partitions) {
        for (ColocatedPartition partition : partitions) {
            int idx = partition.seqNo();
            Path dir = partition.directory();

            HebbianGraph hebb = hebbian(idx);
            if (hebb != null) hebb.save(StorageLayout.hebbianGraph(dir));

            TemporalChain temp = temporal(idx);
            if (temp != null) temp.save(StorageLayout.temporalChain(dir));

            EntityGraph entity = entity(idx);
            if (entity != null) entity.save(StorageLayout.entityGraph(dir));
        }

        log.info("Saved graphs for {} partitions", partitions.size());
    }

    /**
     * Returns the total Hebbian edge count across all partitions.
     */
    public int totalHebbianEdges() {
        int total = 0;
        for (HebbianGraph hebb : hebbianGraphs) {
            if (hebb != null) total += hebb.totalEdges();
        }
        if (crossHebbianGraph != null) total += crossHebbianGraph.totalEdges();
        return total;
    }

    /**
     * Returns the number of loaded partitions.
     */
    public int partitionCount() {
        return hebbianGraphs.size();
    }

    @Override
    public void close() {
        for (HebbianGraph hebb : hebbianGraphs) {
            if (hebb != null) {
                try { hebb.close(); } catch (Exception e) { /* log and continue */ }
            }
        }
        for (TemporalChain chain : temporalChains) {
            if (chain != null) {
                try { chain.close(); } catch (Exception e) { /* log and continue */ }
            }
        }
        for (EntityGraph entity : entityGraphs) {
            if (entity != null) {
                try { entity.close(); } catch (Exception e) { /* log and continue */ }
            }
        }
        if (crossHebbianGraph != null) {
            try { crossHebbianGraph.close(); } catch (Exception e) { /* log and continue */ }
        }
        log.info("PartitionedGraphManager closed ({} partitions)", hebbianGraphs.size());
    }
}
