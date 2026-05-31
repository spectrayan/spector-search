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
