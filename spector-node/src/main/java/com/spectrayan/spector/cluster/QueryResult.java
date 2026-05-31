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
package com.spectrayan.spector.cluster;

import java.util.List;

import com.spectrayan.spector.index.ScoredResult;

/**
 * Result of a distributed query fan-out and merge operation.
 *
 * @param results       merged global top-K results ordered by descending score, deduplicated by document ID
 * @param timedOutShards list of shard IDs that did not respond within the configured timeout
 * @param partial       true if at least one shard timed out but others responded successfully
 * @param error         error message if all shards were unreachable; null otherwise
 */
public record QueryResult(
        List<ScoredResult> results,
        List<String> timedOutShards,
        boolean partial,
        String error
) {

    /**
     * Creates a successful (complete) query result with no timeouts.
     */
    public static QueryResult complete(List<ScoredResult> results) {
        return new QueryResult(results, List.of(), false, null);
    }

    /**
     * Creates a partial query result where some shards timed out.
     */
    public static QueryResult partial(List<ScoredResult> results, List<String> timedOutShards) {
        return new QueryResult(results, timedOutShards, true, null);
    }

    /**
     * Creates an empty result indicating all shards were unreachable.
     */
    public static QueryResult allShardsUnreachable(List<String> shardIds) {
        return new QueryResult(List.of(), shardIds, false,
                "All shards unreachable: " + String.join(", ", shardIds));
    }
}
