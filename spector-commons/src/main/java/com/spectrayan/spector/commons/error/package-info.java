/**
 * Spector exception framework — structured error codes, exception hierarchy,
 * and categorized error handling.
 *
 * <p>This package provides the foundation for all Spector error handling:
 * <ul>
 *   <li>{@link com.spectrayan.spector.commons.error.ErrorCode} — central registry of all
 *       error codes ({@code SPE-XXX-YYY} schema)</li>
 *   <li>{@link com.spectrayan.spector.commons.error.ErrorCategory} — error category
 *       definitions with numeric ranges</li>
 *   <li>{@link com.spectrayan.spector.commons.error.SpectorException} — abstract
 *       base exception carrying an {@code ErrorCode}</li>
 * </ul>
 *
 * <p>Module-specific exception subclasses live in their respective module packages.
 *
 * @see com.spectrayan.spector.commons.error.ErrorCode
 */
package com.spectrayan.spector.commons.error;
