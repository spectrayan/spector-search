/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://github.com/spectrayan/spector/blob/main/spector-memory/LICENSE
 *
 * Change Date: May 27, 2030
 * Change License: Apache License, Version 2.0
 */
package com.spectrayan.spector.memory.namespace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks and enforces resource quotas for a namespace.
 *
 * <h3>Soft Warning at 70%</h3>
 * <p>Per user requirement, when a namespace reaches 70% of any quota,
 * a warning is logged. This gives operators time to provision more capacity
 * or archive old data before hard limits are hit.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>All fields are volatile; checks are non-blocking. The slight race
 * between check and update is acceptable — over-ingesting by one memory
 * before the next check catches it is harmless.</p>
 *
 * @see NamespaceConfig
 */
public final class NamespaceQuotas {

    private static final Logger log = LoggerFactory.getLogger(NamespaceQuotas.class);

    /** Soft warning threshold: 70% of quota. */
    public static final float SOFT_WARNING_THRESHOLD = 0.70f;

    private final NamespaceConfig config;
    private volatile long currentMemoryCount;
    private volatile int currentPartitionCount;
    private volatile long currentStorageBytes;

    // Track whether we've already emitted soft warnings (avoid log spam)
    private volatile boolean memoryWarningEmitted;
    private volatile boolean partitionWarningEmitted;
    private volatile boolean storageWarningEmitted;

    /**
     * Creates a quota tracker for the given namespace config.
     */
    public NamespaceQuotas(NamespaceConfig config) {
        this.config = config;
    }

    /**
     * Updates the current memory count and checks quotas.
     *
     * @param count current total memory count
     */
    public void updateMemoryCount(long count) {
        this.currentMemoryCount = count;
        checkMemoryQuota();
    }

    /**
     * Updates the current partition count and checks quotas.
     *
     * @param count current partition count
     */
    public void updatePartitionCount(int count) {
        this.currentPartitionCount = count;
        checkPartitionQuota();
    }

    /**
     * Updates the current storage usage and checks quotas.
     *
     * @param bytes current storage bytes
     */
    public void updateStorageBytes(long bytes) {
        this.currentStorageBytes = bytes;
        checkStorageQuota();
    }

    /**
     * Checks if the memory quota has been exceeded.
     *
     * @return true if the memory count exceeds the configured maximum
     */
    public boolean isMemoryQuotaExceeded() {
        return config.maxMemories() > 0 && currentMemoryCount >= config.maxMemories();
    }

    /**
     * Checks if the partition quota has been exceeded.
     *
     * @return true if the partition count exceeds the configured maximum
     */
    public boolean isPartitionQuotaExceeded() {
        return config.maxPartitions() > 0 && currentPartitionCount >= config.maxPartitions();
    }

    /**
     * Checks if the storage quota has been exceeded.
     *
     * @return true if the storage bytes exceed the configured maximum
     */
    public boolean isStorageQuotaExceeded() {
        return config.maxStorageBytes() > 0 && currentStorageBytes >= config.maxStorageBytes();
    }

    /**
     * Checks if any quota has been exceeded.
     *
     * @return true if any quota is exceeded
     */
    public boolean isAnyQuotaExceeded() {
        return isMemoryQuotaExceeded() || isPartitionQuotaExceeded() || isStorageQuotaExceeded();
    }

    /**
     * Returns the memory usage ratio (0.0 to 1.0), or 0 if unlimited.
     */
    public float memoryUsageRatio() {
        if (config.maxMemories() <= 0) return 0f;
        return (float) currentMemoryCount / config.maxMemories();
    }

    /**
     * Returns the partition usage ratio (0.0 to 1.0), or 0 if unlimited.
     */
    public float partitionUsageRatio() {
        if (config.maxPartitions() <= 0) return 0f;
        return (float) currentPartitionCount / config.maxPartitions();
    }

    /**
     * Returns the storage usage ratio (0.0 to 1.0), or 0 if unlimited.
     */
    public float storageUsageRatio() {
        if (config.maxStorageBytes() <= 0) return 0f;
        return (float) currentStorageBytes / config.maxStorageBytes();
    }

    /** Current tracked values. */
    public long currentMemoryCount() { return currentMemoryCount; }
    public int currentPartitionCount() { return currentPartitionCount; }
    public long currentStorageBytes() { return currentStorageBytes; }

    // ── Soft warning checks ──

    private void checkMemoryQuota() {
        if (config.maxMemories() <= 0) return;
        float ratio = memoryUsageRatio();

        if (ratio >= 1.0f) {
            log.error("Namespace '{}' MEMORY QUOTA EXCEEDED: {}/{} memories",
                    config.id(), currentMemoryCount, config.maxMemories());
        } else if (ratio >= SOFT_WARNING_THRESHOLD && !memoryWarningEmitted) {
            memoryWarningEmitted = true;
            log.warn("Namespace '{}' memory quota at {:.0f}%: {}/{} memories",
                    config.id(), ratio * 100, currentMemoryCount, config.maxMemories());
        }
    }

    private void checkPartitionQuota() {
        if (config.maxPartitions() <= 0) return;
        float ratio = partitionUsageRatio();

        if (ratio >= 1.0f) {
            log.error("Namespace '{}' PARTITION QUOTA EXCEEDED: {}/{} partitions",
                    config.id(), currentPartitionCount, config.maxPartitions());
        } else if (ratio >= SOFT_WARNING_THRESHOLD && !partitionWarningEmitted) {
            partitionWarningEmitted = true;
            log.warn("Namespace '{}' partition quota at {:.0f}%: {}/{} partitions",
                    config.id(), ratio * 100, currentPartitionCount, config.maxPartitions());
        }
    }

    private void checkStorageQuota() {
        if (config.maxStorageBytes() <= 0) return;
        float ratio = storageUsageRatio();

        if (ratio >= 1.0f) {
            log.error("Namespace '{}' STORAGE QUOTA EXCEEDED: {}/{} bytes",
                    config.id(), currentStorageBytes, config.maxStorageBytes());
        } else if (ratio >= SOFT_WARNING_THRESHOLD && !storageWarningEmitted) {
            storageWarningEmitted = true;
            log.warn("Namespace '{}' storage quota at {:.0f}%: {}/{} bytes",
                    config.id(), ratio * 100, currentStorageBytes, config.maxStorageBytes());
        }
    }
}
