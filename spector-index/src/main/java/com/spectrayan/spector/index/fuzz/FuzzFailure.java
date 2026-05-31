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
package com.spectrayan.spector.index.fuzz;

/**
 * Records a single failure encountered during a fuzz run.
 *
 * @param operationIndex the index of the operation that caused the failure (0-based)
 * @param operation      the operation that triggered the failure
 * @param errorClass     the class name of the exception
 * @param errorMessage   the exception message
 * @param reproducerSeed the seed to reproduce this specific operation sequence
 */
public record FuzzFailure(
        int operationIndex,
        FuzzOperation operation,
        String errorClass,
        String errorMessage,
        long reproducerSeed
) {}
