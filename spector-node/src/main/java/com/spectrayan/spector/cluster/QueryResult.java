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
