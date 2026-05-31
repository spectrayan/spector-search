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
package com.spectrayan.spector.commons.valhalla;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record or class as a candidate for migration to a
 * <a href="https://openjdk.org/jeps/401">JEP 401 Value Class</a>
 * when Project Valhalla lands in a GA JDK release.
 *
 * <h3>Requirements for Value Class Migration</h3>
 * <ul>
 *   <li>All instance fields must be {@code final} (records satisfy this by default)</li>
 *   <li>No {@code synchronized} blocks or use as a monitor</li>
 *   <li>No identity-sensitive operations ({@code ==} comparison, {@code System.identityHashCode})</li>
 *   <li>No subclasses (records are implicitly {@code final})</li>
 * </ul>
 *
 * <h3>Expected Benefits</h3>
 * <ul>
 *   <li><b>Heap flattening</b> — value arrays store fields contiguously, eliminating object headers</li>
 *   <li><b>Scalarization</b> — JIT can decompose value objects into registers, avoiding allocation</li>
 *   <li><b>Cache locality</b> — contiguous memory layout eliminates pointer chasing in arrays</li>
 * </ul>
 *
 * <p>On the {@code labs/valhalla} branch, annotated types are converted to {@code value record}.
 * On {@code main}, this annotation serves as documentation for future migration.</p>
 *
 * @see <a href="https://openjdk.org/jeps/401">JEP 401: Value Classes and Objects (Preview)</a>
 * @see <a href="https://openjdk.org/projects/valhalla/">Project Valhalla</a>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ValueCandidate {

    /**
     * Brief rationale for why this type is a good value class candidate.
     */
    String reason() default "";

    /**
     * Estimated allocation frequency on the hot path.
     */
    Frequency hotPathFrequency() default Frequency.HIGH;

    /**
     * Allocation frequency categories.
     */
    enum Frequency {
        /** Millions of allocations per search (e.g., HNSW neighbor candidates). */
        CRITICAL,
        /** Thousands of allocations per query (e.g., result sets). */
        HIGH,
        /** Tens of allocations per request (e.g., response wrappers). */
        MEDIUM,
        /** Rarely allocated on the hot path. */
        LOW
    }
}
