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
package com.spectrayan.spector.memory.hebbian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-heap adjacency list for full Hebbian graph associations (V2).
 *
 * <h3>Biological Analog: Cortical Network Wiring</h3>
 * <p>In the cortex, neurons form complex networks where activating one node
 * (memory) spreads activation to connected nodes. This graph stores explicit
 * memory-to-memory edges with association weights.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Off-heap adjacency list backed by {@link MemorySegment}</li>
 *   <li>Bounded degree: max 20 neighbors per memory (prevents graph explosion)</li>
 *   <li>Edge weight = co-recall count (strengthened each time both are recalled together)</li>
 *   <li>Enables spreading activation: "if you recalled A, also consider B and C"</li>
 * </ul>
 */
public final class HebbianGraph implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HebbianGraph.class);

    /** Maximum number of Hebbian neighbors per memory. */
    public static final int MAX_DEGREE = 20;

    /** Bytes per edge: 4B (neighbor index) + 4B (weight as float). */
    private static final int EDGE_BYTES = 8;

    /** Bytes per node: 4B (degree) + MAX_DEGREE * EDGE_BYTES. */
    private static final int NODE_BYTES = 4 + MAX_DEGREE * EDGE_BYTES;

    private final Arena arena;
    private final MemorySegment segment;
    private final int capacity;

    /**
     * Creates a Hebbian graph.
     *
     * @param capacity maximum number of nodes (memories)
     */
    public HebbianGraph(int capacity) {
        this.capacity = capacity;
        this.arena = Arena.ofShared();
        this.segment = arena.allocate((long) NODE_BYTES * capacity);
        // Zero-initialize (all degrees start at 0)
        segment.fill((byte) 0);

        log.info("HebbianGraph initialized: capacity={}, memory={}KB",
                capacity, (long) NODE_BYTES * capacity / 1024);
    }

    /**
     * Adds or strengthens a bidirectional Hebbian edge between two memories.
     *
     * @param nodeA index of first memory
     * @param nodeB index of second memory
     * @param weightDelta weight to add to the edge (default: 1.0)
     */
    public synchronized void strengthen(int nodeA, int nodeB, float weightDelta) {
        addOrUpdateEdge(nodeA, nodeB, weightDelta);
        addOrUpdateEdge(nodeB, nodeA, weightDelta);
    }

    /**
     * Returns the Hebbian neighbors of a memory, sorted by descending weight.
     *
     * @param node memory index
     * @return list of (neighborIndex, weight) pairs
     */
    public List<HebbianEdge> neighbors(int node) {
        long nodeOffset = (long) node * NODE_BYTES;
        int degree = segment.get(ValueLayout.JAVA_INT, nodeOffset);

        List<HebbianEdge> edges = new ArrayList<>(degree);
        for (int i = 0; i < degree; i++) {
            long edgeOffset = nodeOffset + 4 + (long) i * EDGE_BYTES;
            int neighbor = segment.get(ValueLayout.JAVA_INT, edgeOffset);
            float weight = segment.get(ValueLayout.JAVA_FLOAT, edgeOffset + 4);
            edges.add(new HebbianEdge(neighbor, weight));
        }

        edges.sort((a, b) -> Float.compare(b.weight(), a.weight()));
        return edges;
    }

    /**
     * Returns the degree (number of Hebbian edges) for a node.
     */
    public int degree(int node) {
        return segment.get(ValueLayout.JAVA_INT, (long) node * NODE_BYTES);
    }

    private void addOrUpdateEdge(int from, int to, float weightDelta) {
        long nodeOffset = (long) from * NODE_BYTES;
        int degree = segment.get(ValueLayout.JAVA_INT, nodeOffset);

        // Check if edge already exists
        for (int i = 0; i < degree; i++) {
            long edgeOffset = nodeOffset + 4 + (long) i * EDGE_BYTES;
            int neighbor = segment.get(ValueLayout.JAVA_INT, edgeOffset);
            if (neighbor == to) {
                // Strengthen existing edge
                float weight = segment.get(ValueLayout.JAVA_FLOAT, edgeOffset + 4);
                segment.set(ValueLayout.JAVA_FLOAT, edgeOffset + 4, weight + weightDelta);
                return;
            }
        }

        // Add new edge (if room)
        if (degree < MAX_DEGREE) {
            long edgeOffset = nodeOffset + 4 + (long) degree * EDGE_BYTES;
            segment.set(ValueLayout.JAVA_INT, edgeOffset, to);
            segment.set(ValueLayout.JAVA_FLOAT, edgeOffset + 4, weightDelta);
            segment.set(ValueLayout.JAVA_INT, nodeOffset, degree + 1);
        } else {
            // Replace weakest edge if new weight exceeds it
            replaceWeakest(nodeOffset, degree, to, weightDelta);
        }
    }

    private void replaceWeakest(long nodeOffset, int degree, int newNeighbor, float newWeight) {
        float minWeight = Float.MAX_VALUE;
        int minIndex = -1;

        for (int i = 0; i < degree; i++) {
            long edgeOffset = nodeOffset + 4 + (long) i * EDGE_BYTES;
            float weight = segment.get(ValueLayout.JAVA_FLOAT, edgeOffset + 4);
            if (weight < minWeight) {
                minWeight = weight;
                minIndex = i;
            }
        }

        if (newWeight > minWeight && minIndex >= 0) {
            long edgeOffset = nodeOffset + 4 + (long) minIndex * EDGE_BYTES;
            segment.set(ValueLayout.JAVA_INT, edgeOffset, newNeighbor);
            segment.set(ValueLayout.JAVA_FLOAT, edgeOffset + 4, newWeight);
        }
    }

    /**
     * Immutable Hebbian edge record.
     *
     * @param neighborIndex index of the connected memory
     * @param weight        association strength
     */
    public record HebbianEdge(int neighborIndex, float weight) {}

    // ── V3: Edge Decay + Session Boundaries + Spreading Activation ──

    private long lastActivityMs = System.currentTimeMillis();
    private long sessionBoundaryMs = 5 * 60 * 1000L; // 5 minutes default

    /**
     * Configures the session boundary inactivity threshold.
     *
     * @param durationMs milliseconds of inactivity that defines a session break
     */
    public void setSessionBoundary(long durationMs) {
        this.sessionBoundaryMs = durationMs;
    }

    /**
     * Checks if a new session has started (inactivity exceeded boundary).
     *
     * @return true if a new session has started since the last activity
     */
    public boolean isNewSession() {
        long now = System.currentTimeMillis();
        boolean isNew = (now - lastActivityMs) > sessionBoundaryMs;
        lastActivityMs = now;
        return isNew;
    }

    /**
     * Decays all edge weights by a factor (V3: called during ReflectDaemon cycles).
     *
     * <p>Unused associations weaken over time — edges that are never re-strengthened
     * eventually drop to zero and get replaced by new associations.</p>
     *
     * @param decayFactor multiplier (e.g., 0.9 = 10% decay per cycle)
     * @return number of edges that dropped below threshold and were removed
     */
    public synchronized int decayEdges(float decayFactor) {
        int removed = 0;
        float removalThreshold = 0.01f; // edges below this are effectively dead

        for (int node = 0; node < capacity; node++) {
            long nodeOffset = (long) node * NODE_BYTES;
            int degree = segment.get(ValueLayout.JAVA_INT, nodeOffset);
            int newDegree = 0;

            for (int i = 0; i < degree; i++) {
                long edgeOffset = nodeOffset + 4 + (long) i * EDGE_BYTES;
                float weight = segment.get(ValueLayout.JAVA_FLOAT, edgeOffset + 4);
                float decayed = weight * decayFactor;

                if (decayed >= removalThreshold) {
                    // Keep edge — compact if needed
                    if (newDegree != i) {
                        long newOffset = nodeOffset + 4 + (long) newDegree * EDGE_BYTES;
                        int neighbor = segment.get(ValueLayout.JAVA_INT, edgeOffset);
                        segment.set(ValueLayout.JAVA_INT, newOffset, neighbor);
                        segment.set(ValueLayout.JAVA_FLOAT, newOffset + 4, decayed);
                    } else {
                        segment.set(ValueLayout.JAVA_FLOAT, edgeOffset + 4, decayed);
                    }
                    newDegree++;
                } else {
                    removed++;
                }
            }

            segment.set(ValueLayout.JAVA_INT, nodeOffset, newDegree);
        }

        if (removed > 0) {
            log.debug("Hebbian edge decay: {} edges removed (factor={})", removed, decayFactor);
        }
        return removed;
    }

    /**
     * Returns the Hebbian neighbors of a memory at a given depth (spreading activation).
     *
     * <p>Depth 1 = direct neighbors. Depth 2 = neighbors of neighbors.
     * Activation strength decreases with each hop.</p>
     *
     * @param node  starting memory index
     * @param depth activation depth (1-3 recommended)
     * @return list of activated edges with compound weights
     */
    public List<HebbianEdge> activateNeighbors(int node, int depth) {
        List<HebbianEdge> activated = new ArrayList<>();
        activateRecursive(node, depth, 1.0f, activated, new java.util.HashSet<>());
        activated.sort((a, b) -> Float.compare(b.weight(), a.weight()));
        return activated;
    }

    private void activateRecursive(int node, int depth, float attenuation,
                                    List<HebbianEdge> activated, java.util.Set<Integer> visited) {
        if (depth <= 0 || visited.contains(node)) return;
        visited.add(node);

        for (HebbianEdge edge : neighbors(node)) {
            float compoundWeight = edge.weight() * attenuation;
            if (compoundWeight > 0.01f && !visited.contains(edge.neighborIndex())) {
                activated.add(new HebbianEdge(edge.neighborIndex(), compoundWeight));
                activateRecursive(edge.neighborIndex(), depth - 1, compoundWeight * 0.5f,
                        activated, visited);
            }
        }
    }

    @Override
    public void close() {
        log.info("HebbianGraph closing (capacity={})", capacity);
        arena.close();
    }
}

