package com.spectrayan.spector.node.api.dto;

import com.spectrayan.spector.memory.ReflectReport;

/**
 * Response DTO for {@code POST /memory/reflect}.
 *
 * @param consolidatedCount  number of memories consolidated during the reflect cycle
 * @param tombstonedCount    number of memories tombstoned during the reflect cycle
 * @param compactedPartitions number of storage partitions compacted
 * @param temporalPrunedCount number of temporal links pruned
 * @param durationMs         total duration of the reflect cycle in milliseconds
 */
public record ReflectResponseDto(
        int consolidatedCount,
        int tombstonedCount,
        int compactedPartitions,
        int temporalPrunedCount,
        long durationMs
) {

    /**
     * Creates a response DTO from the domain model.
     */
    public static ReflectResponseDto from(ReflectReport report) {
        return new ReflectResponseDto(
                report.consolidatedCount(),
                report.tombstonedCount(),
                report.compactedPartitions(),
                report.temporalPrunedCount(),
                report.duration().toMillis()
        );
    }
}
