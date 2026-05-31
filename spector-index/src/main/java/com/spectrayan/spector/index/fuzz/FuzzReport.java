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

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Report produced by a completed fuzz testing run.
 *
 * @param totalOps          total number of operations executed
 * @param errors            total number of errors encountered
 * @param duration          wall-clock duration of the run
 * @param failures          list of individual failures
 * @param uniqueErrorClasses set of unique exception class names encountered
 */
public record FuzzReport(
        int totalOps,
        int errors,
        Duration duration,
        List<FuzzFailure> failures,
        Set<String> uniqueErrorClasses
) {}
