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
package com.spectrayan.spector.bench.cognitive;

import java.util.List;

/**
 * Thrown when referential integrity validation fails during dataset loading.
 *
 * <p>Contains a descriptive message listing all detected violations, enabling
 * developers to fix multiple issues in a single pass rather than encountering
 * them one at a time.</p>
 */
public class DatasetValidationException extends RuntimeException {

    private final List<String> violations;

    /**
     * Creates a validation exception with a list of all detected violations.
     *
     * @param violations descriptive messages for each referential integrity violation
     */
    public DatasetValidationException(List<String> violations) {
        super("Dataset validation failed with " + violations.size() + " violation(s): "
                + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Creates a validation exception with a single violation message.
     *
     * @param message descriptive message for the violation
     */
    public DatasetValidationException(String message) {
        super(message);
        this.violations = List.of(message);
    }

    /**
     * Returns the list of all detected violations.
     *
     * @return unmodifiable list of violation messages
     */
    public List<String> getViolations() {
        return violations;
    }
}
