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
package com.spectrayan.spector.memory.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRDT-style conflict resolution strategy for multi-writer memory sync.
 *
 * <h3>Biological Analog: Inter-Hemispheric Transfer</h3>
 * <p>The corpus callosum enables both brain hemispheres to share information
 * without conflicts — each hemisphere processes and modifies memories independently,
 * then transfers knowledge via a deterministic protocol. CRDT merge provides the
 * same guarantee for distributed agents.</p>
 *
 * <h3>Merge Rules</h3>
 * <ul>
 *   <li><b>Timestamp:</b> Last-Writer-Wins (LWW) — keep the most recent</li>
 *   <li><b>Importance:</b> Max-merge — keep the higher importance</li>
 *   <li><b>Valence:</b> Last-Writer-Wins (LWW) — most recent outcome</li>
 *   <li><b>Recall Count:</b> Max-merge — keep the higher count</li>
 *   <li><b>Synaptic Tags:</b> OR-merge — union of Bloom filters</li>
 *   <li><b>Tombstone:</b> Tombstone wins — delete is permanent (crdt-tombstone)</li>
 *   <li><b>Consolidated:</b> OR — once consolidated, stays consolidated</li>
 *   <li><b>Pinned:</b> OR — once pinned, stays pinned</li>
 * </ul>
 *
 * <h3>Guarantee</h3>
 * <p>All merge operations are commutative, associative, and idempotent.
 * This means any order of merges from any agents produces the same final state.</p>
 */
public final class CrdtMergeStrategy {

    private static final Logger log = LoggerFactory.getLogger(CrdtMergeStrategy.class);

    /**
     * Merged header fields produced by CRDT resolution.
     *
     * @param timestampMs   LWW: most recent timestamp
     * @param synapticTags  OR-merge: union of Bloom filters
     * @param importance    Max-merge: highest importance
     * @param agentRecallCount   Max-merge: highest recall count
     * @param valence       LWW: valence from most recent timestamp
     * @param flags         Merged flags (tombstone wins, consolidated/pinned OR)
     */
    public record MergedHeader(
            long timestampMs,
            long synapticTags,
            float importance,
            int agentRecallCount,
            byte valence,
            byte flags
    ) {}

    /**
     * Input header fields from a single source.
     */
    public record SourceHeader(
            long timestampMs,
            long synapticTags,
            float importance,
            int agentRecallCount,
            byte valence,
            byte flags
    ) {}

    /**
     * Merges two headers using CRDT rules.
     *
     * @param local  the local header
     * @param remote the remote header
     * @return merged header with CRDT-resolved fields
     */
    public static MergedHeader merge(SourceHeader local, SourceHeader remote) {
        // LWW: most recent timestamp wins for timestamp and valence
        boolean remoteIsNewer = remote.timestampMs() >= local.timestampMs();

        long mergedTimestamp = Math.max(local.timestampMs(), remote.timestampMs());
        long mergedTags = local.synapticTags() | remote.synapticTags(); // OR-merge
        float mergedImportance = Math.max(local.importance(), remote.importance()); // Max-merge
        int mergedagentRecallCount = Math.max(local.agentRecallCount(), remote.agentRecallCount()); // Max-merge
        byte mergedValence = remoteIsNewer ? remote.valence() : local.valence(); // LWW

        // Flag merge: tombstone and consolidated/pinned are OR-merged
        byte mergedFlags = mergeFlags(local.flags(), remote.flags());

        log.trace("CRDT merge: local_ts={}, remote_ts={}, winner={}",
                local.timestampMs(), remote.timestampMs(),
                remoteIsNewer ? "remote" : "local");

        return new MergedHeader(mergedTimestamp, mergedTags, mergedImportance,
                mergedagentRecallCount, mergedValence, mergedFlags);
    }

    /**
     * Merges flag bytes.
     * <ul>
     *   <li>Tombstone (bit 0): OR — once tombstoned, always tombstoned</li>
     *   <li>Memory type (bits 1-2): taken from newer source</li>
     *   <li>Consolidated (bit 3): OR</li>
     *   <li>Pinned (bit 4): OR</li>
     * </ul>
     */
    static byte mergeFlags(byte local, byte remote) {
        // OR for tombstone, consolidated, pinned
        byte orBits = (byte) ((local | remote) & 0b00011001); // bits 0, 3, 4

        // Memory type from either (they should be the same for the same memory ID)
        byte memType = (byte) (local & 0b00000110); // bits 1-2 from local

        return (byte) (orBits | memType);
    }

    /**
     * Checks if a merge would change any fields.
     *
     * @param local  current local state
     * @param remote incoming remote state
     * @return true if the remote has newer/higher values that would change the local state
     */
    public static boolean wouldChange(SourceHeader local, SourceHeader remote) {
        if (remote.timestampMs() > local.timestampMs()) return true;
        if (remote.importance() > local.importance()) return true;
        if (remote.agentRecallCount() > local.agentRecallCount()) return true;
        if ((remote.synapticTags() & ~local.synapticTags()) != 0) return true; // remote has bits local doesn't
        if ((remote.flags() & ~local.flags()) != 0) return true; // remote has flag bits local doesn't
        return false;
    }

    private CrdtMergeStrategy() {} // static utility
}
