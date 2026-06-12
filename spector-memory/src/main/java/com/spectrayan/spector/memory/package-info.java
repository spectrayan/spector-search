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
/**
 * Spector Memory — Biologically-Inspired Cognitive Memory for Autonomous AI Agents.
 *
 * <p>16 neuroscience mechanisms running natively on Java Panama — from dopamine-driven
 * surprise detection to hippocampal sleep consolidation — with zero-GC, SIMD-accelerated
 * off-heap storage.</p>
 *
 * <h3>Biological Package Structure</h3>
 * <ul>
 *   <li>{@code cortex/} — Memory tiers (Working, Episodic, Semantic, Procedural) + source monitoring</li>
 *   <li>{@code synapse/} — 64-byte header layout, fused SIMD scoring, Bloom filter tags, bucket decay</li>
 *   <li>{@code dopamine/} — Adaptive surprise detection (z-score importance assignment)</li>
 *   <li>{@code amygdala/} — Emotional valence and outcome-driven reinforcement (V2)</li>
 *   <li>{@code hippocampus/} — REM/Deep Sleep consolidation daemon</li>
 *   <li>{@code hebbian/} — Spreading activation and co-occurrence tracking (V2)</li>
 *   <li>{@code interference/} — Semantic deduplication with merge-on-ingest</li>
 *   <li>{@code inhibition/} — Session-level recall suppression (V2)</li>
 *   <li>{@code habituation/} — Anti-filter-bubble result diversity (V3)</li>
 *   <li>{@code prospective/} — Time-triggered future recall (V3)</li>
 *   <li>{@code metamemory/} — Memory introspection and self-awareness (V2)</li>
 *   <li>{@code sync/} — WAL-based CloudSync for distributed memory (V2)</li>
 * </ul>
 *
 * <h3>Entry Point</h3>
 * <p>Use {@link com.spectrayan.spector.memory.SpectorMemory#builder()} to construct
 * a memory instance with your {@link com.spectrayan.spector.embed.EmbeddingProvider}.</p>
 *
 * @see com.spectrayan.spector.memory.SpectorMemory
 */
package com.spectrayan.spector.memory;
