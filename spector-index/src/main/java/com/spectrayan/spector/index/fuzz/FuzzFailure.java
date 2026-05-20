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
