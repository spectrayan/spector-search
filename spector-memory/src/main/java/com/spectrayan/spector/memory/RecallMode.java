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

/**
 * Controls whether recall mutates memory state.
 *
 * <h3>The Statefulness Design</h3>
 * <p>Spector Memory is biologically-inspired: recalling a memory strengthens it,
 * just as in the human brain (Long-Term Potentiation). This means the same query
 * can return different results over time — a feature, not a bug, for personal
 * assistants and long-lived agents.</p>
 *
 * <p>However, some use cases (testing, auditing, A/B evaluation) need deterministic,
 * side-effect-free recall. {@code RecallMode} gives explicit control.</p>
 *
 * <h3>Mutation Inventory</h3>
 * <table>
 *   <tr><th>Mutation</th><th>LEARN</th><th>OBSERVE</th></tr>
 *   <tr><td>Habituation penalty counter</td><td>✅ Increments</td><td>❌ Read-only</td></tr>
 *   <tr><td>Inhibition-of-return timestamps</td><td>✅ Records</td><td>❌ Skipped</td></tr>
 *   <tr><td>Semantic satiation cache</td><td>✅ Updates</td><td>❌ Skipped</td></tr>
 *   <tr><td>Post-recall listeners (LTP, Hebbian, ACT-R)</td><td>✅ Fires</td><td>❌ Skipped</td></tr>
 *   <tr><td>Retrieval mode cache (lateral feedback)</td><td>✅ Updates</td><td>❌ Skipped</td></tr>
 * </table>
 *
 * @see RecallOptions.Builder#recallMode(RecallMode)
 */
public enum RecallMode {

    /**
     * Full biological memory — recall mutates state.
     *
     * <p>Habituation, LTP reconsolidation, Hebbian co-activation, ACT-R recall
     * timestamps, and inhibition-of-return all fire normally. Repeated recall
     * of the same query will return progressively different results as the
     * memory landscape evolves.</p>
     *
     * <p>Use case: Personal assistants, roleplay AI, long-lived coding agents,
     * home automation agents — any system where memories should strengthen
     * through use.</p>
     */
    LEARN,

    /**
     * Pure observation — recall returns results but mutates NOTHING.
     *
     * <p>All post-recall side effects are suppressed. The same query with the
     * same memory state always returns the same results (given the same
     * timestamp). Habituation counters are read but not incremented.</p>
     *
     * <p>Use case: Debugging, A/B testing, batch evaluation (LoCoMo),
     * enterprise compliance (audit-safe queries), unit testing.</p>
     */
    OBSERVE,

    /**
     * Replay from WAL snapshot — reads from a frozen point-in-time state.
     *
     * <p>Reconstructs memory state as it existed at a specific timestamp by
     * replaying WAL events. No mutations occur. Used for debugging specific
     * recall failures or comparing algorithm changes against historical state.</p>
     *
     * <p>Use case: "Why did the agent retrieve X instead of Y at 2pm yesterday?"</p>
     *
     * <p><b>Not yet implemented.</b> Requires WAL point-in-time reconstruction
     * (replaying ingestion, reinforcement, and suppression events to rebuild
     * off-heap header state at a target timestamp). See roadmap.</p>
     *
     * @throws com.spectrayan.spector.commons.error.SpectorValidationException
     *         SPE-310-012 if used before implementation
     */
    REPLAY
}
