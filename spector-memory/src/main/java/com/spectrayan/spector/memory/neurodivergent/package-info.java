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
 * Neurodivergent cognitive profile support — configurable mechanics for
 * divergent thinking, hyperfocus, and systematizing behavior in AI agents.
 *
 * <h3>Overview</h3>
 * <p>This package implements four biologically-inspired cognitive mechanics:</p>
 * <ol>
 *   <li><b>Hyperfocus</b> ({@link com.spectrayan.spector.memory.neurodivergent.HyperfocusState})
 *       — zero time decay, strict tag gating, TTL with agent self-extension</li>
 *   <li><b>ICNU Fusion</b> ({@link com.spectrayan.spector.memory.neurodivergent.IcnuWeights},
 *       {@link com.spectrayan.spector.memory.neurodivergent.IngestionHints})
 *       — Interest/Challenge/Novelty/Urgency importance computation</li>
 *   <li><b>Lateral Evaluation</b> ({@link com.spectrayan.spector.memory.neurodivergent.LateralEvaluator})
 *       — tracks utility, suppression, and hallucination rates for orthogonal retrieval</li>
 *   <li><b>Lossless Consolidation</b> — pin bit toggle during REM sleep (in ReflectDaemon)</li>
 * </ol>
 *
 * <h3>Design Philosophy</h3>
 * <p>Neurodivergence is not a deficit — it is an alternative optimization strategy.
 * Neurotypical brains optimize for energy efficiency and routine predictability.
 * Neurodivergent brains optimize for lateral synthesis, deep systematizing,
 * novelty-seeking, and hyperfocus — the exact cognitive profile required for
 * groundbreaking discovery and out-of-the-box engineering.</p>
 *
 * @see com.spectrayan.spector.memory.CognitiveProfile
 */
package com.spectrayan.spector.memory.neurodivergent;
