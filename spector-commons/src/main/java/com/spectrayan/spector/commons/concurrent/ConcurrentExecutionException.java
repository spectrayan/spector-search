package com.spectrayan.spector.commons.concurrent;

/**
 * Exception thrown when a concurrent fork-join operation fails.
 *
 * <p>Wraps the root cause of the first failed subtask. In structured
 * concurrency mode, this wraps the {@code FailedException} cause.
 * In classic mode, it wraps the {@code ExecutionException} cause.</p>
 */
public class ConcurrentExecutionException extends Exception {

    /**
     * Creates a new concurrent execution exception.
     *
     * @param message descriptive message
     * @param cause   the underlying exception from the failed task
     */
    public ConcurrentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
