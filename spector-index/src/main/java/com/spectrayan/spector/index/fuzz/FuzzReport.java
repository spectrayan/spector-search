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
