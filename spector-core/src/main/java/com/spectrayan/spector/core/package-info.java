/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * Spector Core — SIMD-accelerated math kernels and similarity functions.
 *
 * <p>This module provides hardware-accelerated vector operations using the
 * Java Vector API (AVX2/AVX-512/NEON/SVE). All similarity computations
 * (cosine, dot-product, Euclidean) are implemented as branchless SIMD
 * kernels that auto-adapt to the host CPU's preferred vector width.</p>
 */
package com.spectrayan.spector.core;
