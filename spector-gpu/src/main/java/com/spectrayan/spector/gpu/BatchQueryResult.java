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
package com.spectrayan.spector.gpu;

import java.util.List;

/**
 * Result for a single query within a batch GPU search operation.
 *
 * <p>Either contains the top-K results or an error message if the query
 * failed during GPU execution. Per-query error isolation ensures that
 * one failing query does not impact others in the same batch.</p>
 *
 * @param results the top-K scored results (empty if error occurred)
 * @param error   the error message if this query failed, null if successful
 */
public record BatchQueryResult(List<BatchSearchResult> results, String error) {

    /**
     * Creates a successful result.
     */
    public static BatchQueryResult success(List<BatchSearchResult> results) {
        return new BatchQueryResult(List.copyOf(results), null);
    }

    /**
     * Creates an error result.
     */
    public static BatchQueryResult failure(String error) {
        return new BatchQueryResult(List.of(), error);
    }

    /**
     * Returns true if this query completed successfully.
     */
    public boolean isSuccess() {
        return error == null;
    }
}
