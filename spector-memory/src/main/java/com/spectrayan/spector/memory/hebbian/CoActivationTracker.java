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

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synaptic tag co-occurrence and STDP tracking for Hebbian learning.
 *
 * <h3>Biological Analog: Hebbian Learning + STDP</h3>
 * <p>"Cells that fire together wire together" (Hebb, 1949). When two neurons
 * fire simultaneously, the synapse between them strengthens. Over time,
 * activating one neuron automatically activates the other — this is the
 * basis of associative memory.</p>
 *
 * <h3>Spike-Timing-Dependent Plasticity (STDP)</h3>
 * <p>STDP extends basic Hebbian learning with <em>temporal direction</em>.
 * If neuron A fires <b>before</b> neuron B (causal), the A→B synapse is
 * <b>strengthened</b> (Long-Term Potentiation). If A fires <b>after</b> B
 * (anti-causal), the B→A synapse is <b>weakened</b> (Long-Term Depression).
 * This produces directed, predictive associations — "tag A predicts tag B."</p>
 *
 * <h3>Dual Data Structures</h3>
 * <ul>
 *   <li><b>Co-Activations</b> (undirected): simple symmetric pair counts.
 *       Used for basic Hebbian spreading activation.</li>
 *   <li><b>Directed Edges</b> (STDP): timestamped, weighted, directional.
 *       Used for predictive/causal association boosting at recall time.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Uses {@link ConcurrentHashMap} throughout. Write-side methods are lock-free.
 * Read-side methods may see slightly stale data under concurrent writes — this
 * is acceptable for soft-scoring signals.</p>
 *
 * @see HebbianCoActivationListener
 */
public final class CoActivationTracker {

    private static final Logger log = LoggerFactory.getLogger(CoActivationTracker.class);

    // ══════════════════════════════════════════════════════════════
    // STDP Constants
    // ══════════════════════════════════════════════════════════════

    /** A+ (LTP amplitude): maximum weight increase for causal pairings. */
    private static final float A_PLUS = 0.1f;

    /** A- (LTD amplitude): maximum weight decrease for anti-causal pairings. */
    private static final float A_MINUS = 0.05f;

    /** τ+ (LTP time constant): causal window in milliseconds. */
    private static final float TAU_PLUS = 30_000f;  // 30 seconds

    /** τ- (LTD time constant): anti-causal window in milliseconds. */
    private static final float TAU_MINUS = 30_000f;  // 30 seconds

    /** Minimum weight (prevent complete erasure). */
    private static final float MIN_WEIGHT = 0.0f;

    /** Maximum weight (prevent runaway potentiation). */
    private static final float MAX_WEIGHT = 1.0f;

    // ══════════════════════════════════════════════════════════════
    // Data Structures
    // ══════════════════════════════════════════════════════════════

    /**
     * Co-activation counts: key = "tagA:tagB" (alphabetically sorted), value = count.
     * Undirected Hebbian associations.
     */
    private final ConcurrentHashMap<String, AtomicInteger> coActivations = new ConcurrentHashMap<>();

    /**
     * STDP directed edges: key = DirectedEdge (source→target), value = EdgeWeight.
     * Tracks causal/temporal relationships between tags.
     */
    private final ConcurrentHashMap<DirectedEdge, EdgeWeight> stdpEdges = new ConcurrentHashMap<>();

    /** Maximum number of tracked undirected pairs to prevent unbounded growth. */
    private final int maxPairs;

    /** Maximum number of tracked STDP edges. */
    private final int maxEdges;

    // ══════════════════════════════════════════════════════════════
    // STDP Records
    // ══════════════════════════════════════════════════════════════

    /**
     * A directed edge between two synaptic tags.
     *
     * <p>Unlike undirected co-activation pairs, directed edges encode
     * <em>temporal order</em>: sourceTag was activated before targetTag.</p>
     */
    public record DirectedEdge(String sourceTag, String targetTag) {
        @Override
        public String toString() {
            return sourceTag + "→" + targetTag;
        }
    }

    /**
     * STDP edge weight with temporal metadata.
     *
     * <p>The weight represents the strength of the causal association.
     * Positive weight = sourceTag predicts targetTag (LTP).
     * Zero weight = no causal relationship detected.</p>
     *
     * @param weight           current STDP weight (0.0 to 1.0)
     * @param lastActivatedMs  epoch millis of last activation
     * @param activationCount  total number of sequential activations
     */
    public record EdgeWeight(float weight, long lastActivatedMs, int activationCount) {
        /** Returns a new EdgeWeight with updated weight and timestamp. */
        public EdgeWeight withUpdate(float deltaWeight, long nowMs) {
            float newWeight = Math.clamp(weight + deltaWeight, MIN_WEIGHT, MAX_WEIGHT);
            return new EdgeWeight(newWeight, nowMs, activationCount + 1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Constructors
    // ══════════════════════════════════════════════════════════════

    /**
     * Creates a co-activation tracker.
     *
     * @param maxPairs maximum tracked undirected pairs before pruning (default: 10_000)
     */
    public CoActivationTracker(int maxPairs) {
        this(maxPairs, maxPairs * 2);  // directed edges are 2× undirected pairs
    }

    /**
     * Creates a co-activation tracker with custom limits.
     *
     * @param maxPairs maximum tracked undirected pairs
     * @param maxEdges maximum tracked STDP directed edges
     */
    public CoActivationTracker(int maxPairs, int maxEdges) {
        this.maxPairs = maxPairs;
        this.maxEdges = maxEdges;
    }

    /**
     * Creates a tracker with default max pairs (10_000) and edges (20_000).
     */
    public CoActivationTracker() {
        this(10_000);
    }

    // ══════════════════════════════════════════════════════════════
    // Undirected Co-Activation (Original Hebbian)
    // ══════════════════════════════════════════════════════════════

    /**
     * Records co-activation of tags that appeared together in a recall result set.
     *
     * <p>For each pair (i, j) where i &lt; j alphabetically, increment the
     * co-activation count.</p>
     *
     * @param tags array of tag strings that appeared together in recall results
     */
    public void recordCoActivation(String... tags) {
        if (tags.length < 2) return;

        for (int i = 0; i < tags.length; i++) {
            for (int j = i + 1; j < tags.length; j++) {
                String key = pairKey(tags[i], tags[j]);

                if (coActivations.size() >= maxPairs && !coActivations.containsKey(key)) {
                    pruneWeakest();
                }

                coActivations.computeIfAbsent(key, k -> new AtomicInteger(0))
                        .incrementAndGet();
            }
        }
    }

    /**
     * Returns the co-activation count for a tag pair.
     *
     * @param tagA first tag
     * @param tagB second tag
     * @return co-activation count (0 if never co-activated)
     */
    public int getCoActivation(String tagA, String tagB) {
        String key = pairKey(tagA, tagB);
        AtomicInteger count = coActivations.get(key);
        return count != null ? count.get() : 0;
    }

    /**
     * Returns the top-N most co-activated tags for a given tag.
     *
     * @param tag   the source tag
     * @param topN  maximum number of associated tags to return
     * @return list of associated tag names sorted by co-activation strength
     */
    public java.util.List<String> getAssociatedTags(String tag, int topN) {
        return coActivations.entrySet().stream()
                .filter(e -> e.getKey().contains(tag + ":") || e.getKey().contains(":" + tag))
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .limit(topN)
                .map(e -> {
                    String[] parts = e.getKey().split(":");
                    return parts[0].equals(tag) ? parts[1] : parts[0];
                })
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    // STDP — Spike-Timing-Dependent Plasticity
    // ══════════════════════════════════════════════════════════════

    /**
     * Records a sequential activation pair for STDP weight update.
     *
     * <p>When tag A is activated before tag B (within the temporal window),
     * the causal A→B edge is <b>strengthened</b> and the anti-causal B→A
     * edge is <b>weakened</b>.</p>
     *
     * <h3>STDP Formula</h3>
     * <pre>
     *   Δt = t_after - t_before
     *
     *   Causal (A→B):      ΔW = +A_plus  × exp(-Δt / τ_plus)
     *   Anti-causal (B→A): ΔW = -A_minus × exp(-Δt / τ_minus)
     * </pre>
     *
     * <p>The exponential decay ensures that closer temporal proximity produces
     * stronger weight updates. Tags activated within seconds of each other
     * get a much stronger association than tags activated minutes apart.</p>
     *
     * @param tagBefore  the tag that was activated first
     * @param tagAfter   the tag that was activated second
     * @param timeBefore epoch millis when tagBefore was activated
     * @param timeAfter  epoch millis when tagAfter was activated
     */
    public void recordSequentialActivation(String tagBefore, String tagAfter,
                                            long timeBefore, long timeAfter) {
        if (tagBefore.equals(tagAfter)) return;
        if (timeAfter < timeBefore) return; // invalid ordering

        long dt = timeAfter - timeBefore;

        // Causal: A→B (strengthen — A predicts B)
        DirectedEdge causal = new DirectedEdge(tagBefore, tagAfter);
        float dW_causal = A_PLUS * (float) Math.exp(-dt / TAU_PLUS);
        updateEdge(causal, dW_causal, timeAfter);

        // Anti-causal: B→A (weaken — B does NOT predict A)
        DirectedEdge antiCausal = new DirectedEdge(tagAfter, tagBefore);
        float dW_anti = -A_MINUS * (float) Math.exp(-dt / TAU_MINUS);
        updateEdge(antiCausal, dW_anti, timeAfter);

        log.trace("STDP: {}→{} Δt={}ms, causal ΔW={}, anti-causal ΔW={}",
                tagBefore, tagAfter, dt,
                String.format("%.4f", dW_causal), String.format("%.4f", dW_anti));
    }

    /**
     * Records sequential activations from an ordered list of tags with timestamps.
     *
     * <p>Each consecutive pair in the list is treated as a sequential activation.
     * This is typically called after recall, using the recall result order as
     * the temporal sequence.</p>
     *
     * @param orderedTags tags in temporal order (first = earliest)
     * @param timestamps  corresponding epoch millis for each tag
     */
    public void recordSequentialActivations(List<String> orderedTags, List<Long> timestamps) {
        if (orderedTags.size() < 2) return;
        if (orderedTags.size() != timestamps.size()) return;

        for (int i = 0; i < orderedTags.size() - 1; i++) {
            recordSequentialActivation(
                    orderedTags.get(i), orderedTags.get(i + 1),
                    timestamps.get(i), timestamps.get(i + 1));
        }
    }

    /**
     * Returns the STDP predictive strength from query tags to a result's tags.
     *
     * <p>Computes the maximum causal weight from any query tag to any result tag.
     * Higher values mean the query tags strongly predict the result's tags.</p>
     *
     * <p>This is used as a supplementary scoring signal in the recall pipeline:
     * results whose tags are causally predicted by the query get a boost.</p>
     *
     * @param queryTags  tags from the query context
     * @param resultTags tags from a candidate result
     * @return maximum predictive strength (0.0 if no causal link exists)
     */
    public float getPredictiveStrength(List<String> queryTags, String[] resultTags) {
        if (queryTags == null || queryTags.isEmpty() || resultTags == null || resultTags.length == 0) {
            return 0.0f;
        }

        float maxStrength = 0.0f;
        for (String qTag : queryTags) {
            for (String rTag : resultTags) {
                DirectedEdge edge = new DirectedEdge(qTag, rTag);
                EdgeWeight weight = stdpEdges.get(edge);
                if (weight != null && weight.weight() > maxStrength) {
                    maxStrength = weight.weight();
                }
            }
        }
        return maxStrength;
    }

    /**
     * Returns the average predictive strength (mean of all matching edges).
     *
     * @param queryTags  tags from the query context
     * @param resultTags tags from a candidate result
     * @return average predictive strength across all matching edges
     */
    public float getAveragePredictiveStrength(List<String> queryTags, String[] resultTags) {
        if (queryTags == null || queryTags.isEmpty() || resultTags == null || resultTags.length == 0) {
            return 0.0f;
        }

        float sum = 0.0f;
        int count = 0;
        for (String qTag : queryTags) {
            for (String rTag : resultTags) {
                DirectedEdge edge = new DirectedEdge(qTag, rTag);
                EdgeWeight weight = stdpEdges.get(edge);
                if (weight != null && weight.weight() > 0) {
                    sum += weight.weight();
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : 0.0f;
    }

    /**
     * Returns the STDP edge weight for a specific directed edge.
     *
     * @param sourceTag the source tag
     * @param targetTag the target tag
     * @return the edge weight, or null if no edge exists
     */
    public EdgeWeight getEdge(String sourceTag, String targetTag) {
        return stdpEdges.get(new DirectedEdge(sourceTag, targetTag));
    }

    /**
     * Returns the number of STDP directed edges.
     */
    public int edgeCount() {
        return stdpEdges.size();
    }

    // ══════════════════════════════════════════════════════════════
    // Internal Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Updates a directed edge weight via STDP delta.
     */
    private void updateEdge(DirectedEdge edge, float deltaWeight, long nowMs) {
        if (stdpEdges.size() >= maxEdges && !stdpEdges.containsKey(edge)) {
            pruneWeakestEdges();
        }

        stdpEdges.compute(edge, (k, existing) -> {
            if (existing == null) {
                // New edge — initialize with the delta (clamped above 0)
                float initialWeight = Math.max(MIN_WEIGHT, deltaWeight);
                return new EdgeWeight(initialWeight, nowMs, 1);
            }
            return existing.withUpdate(deltaWeight, nowMs);
        });
    }

    /**
     * Returns the number of tracked undirected tag pairs.
     */
    public int pairCount() {
        return coActivations.size();
    }

    /**
     * Prunes the weakest 10% of undirected co-activation pairs.
     */
    private void pruneWeakest() {
        int toPrune = maxPairs / 10;
        coActivations.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(
                        (a, b) -> Integer.compare(a.get(), b.get())))
                .limit(toPrune)
                .map(Map.Entry::getKey)
                .toList() // materialize before removal
                .forEach(coActivations::remove);

        log.debug("Pruned {} weak co-activation pairs (remaining={})",
                toPrune, coActivations.size());
    }

    /**
     * Prunes the weakest 10% of STDP directed edges.
     */
    private void pruneWeakestEdges() {
        int toPrune = maxEdges / 10;
        stdpEdges.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(
                        (a, b) -> Float.compare(a.weight(), b.weight())))
                .limit(toPrune)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(stdpEdges::remove);

        log.debug("Pruned {} weak STDP edges (remaining={})",
                toPrune, stdpEdges.size());
    }

    /**
     * Creates a canonical pair key (alphabetically sorted to avoid A:B vs B:A duplication).
     */
    private static String pairKey(String tagA, String tagB) {
        return tagA.compareTo(tagB) <= 0 ? tagA + ":" + tagB : tagB + ":" + tagA;
    }

    /**
     * Resets all co-activation and STDP data.
     */
    public void reset() {
        coActivations.clear();
        stdpEdges.clear();
    }
}
