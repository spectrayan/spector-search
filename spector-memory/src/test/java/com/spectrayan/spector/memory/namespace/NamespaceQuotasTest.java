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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link NamespaceQuotas} — quota tracking and 70% soft warnings.
 */
class NamespaceQuotasTest {

    @Test
    void unlimited_config_never_exceeded() {
        var config = NamespaceConfig.unlimited("test");
        var quotas = new NamespaceQuotas(config);

        quotas.updateMemoryCount(1_000_000);
        quotas.updatePartitionCount(1000);
        quotas.updateStorageBytes(Long.MAX_VALUE);

        assertThat(quotas.isMemoryQuotaExceeded()).isFalse();
        assertThat(quotas.isPartitionQuotaExceeded()).isFalse();
        assertThat(quotas.isStorageQuotaExceeded()).isFalse();
        assertThat(quotas.isAnyQuotaExceeded()).isFalse();
    }

    @Test
    void memory_quota_exceeded_when_at_limit() {
        var config = NamespaceConfig.withQuotas("test", 100, -1, -1);
        var quotas = new NamespaceQuotas(config);

        quotas.updateMemoryCount(99);
        assertThat(quotas.isMemoryQuotaExceeded()).isFalse();

        quotas.updateMemoryCount(100);
        assertThat(quotas.isMemoryQuotaExceeded()).isTrue();
    }

    @Test
    void partition_quota_exceeded_when_at_limit() {
        var config = NamespaceConfig.withQuotas("test", -1, 10, -1);
        var quotas = new NamespaceQuotas(config);

        quotas.updatePartitionCount(9);
        assertThat(quotas.isPartitionQuotaExceeded()).isFalse();

        quotas.updatePartitionCount(10);
        assertThat(quotas.isPartitionQuotaExceeded()).isTrue();
    }

    @Test
    void storage_quota_exceeded_when_at_limit() {
        long maxBytes = 1024 * 1024; // 1MB
        var config = NamespaceConfig.withQuotas("test", -1, -1, maxBytes);
        var quotas = new NamespaceQuotas(config);

        quotas.updateStorageBytes(maxBytes - 1);
        assertThat(quotas.isStorageQuotaExceeded()).isFalse();

        quotas.updateStorageBytes(maxBytes);
        assertThat(quotas.isStorageQuotaExceeded()).isTrue();
    }

    @Test
    void usage_ratios_computed_correctly() {
        var config = NamespaceConfig.withQuotas("test", 1000, 100, 1024 * 1024);
        var quotas = new NamespaceQuotas(config);

        quotas.updateMemoryCount(500);
        quotas.updatePartitionCount(25);
        quotas.updateStorageBytes(512 * 1024);

        assertThat(quotas.memoryUsageRatio()).isEqualTo(0.5f);
        assertThat(quotas.partitionUsageRatio()).isEqualTo(0.25f);
        assertThat(quotas.storageUsageRatio()).isEqualTo(0.5f);
    }

    @Test
    void usage_ratios_zero_for_unlimited() {
        var config = NamespaceConfig.unlimited("test");
        var quotas = new NamespaceQuotas(config);

        quotas.updateMemoryCount(1000);
        assertThat(quotas.memoryUsageRatio()).isZero();
        assertThat(quotas.partitionUsageRatio()).isZero();
        assertThat(quotas.storageUsageRatio()).isZero();
    }

    @Test
    void isAnyQuotaExceeded_checks_all_dimensions() {
        var config = NamespaceConfig.withQuotas("test", 100, 10, 1024);
        var quotas = new NamespaceQuotas(config);

        quotas.updateMemoryCount(50);
        quotas.updatePartitionCount(5);
        quotas.updateStorageBytes(512);
        assertThat(quotas.isAnyQuotaExceeded()).isFalse();

        // Exceed just storage
        quotas.updateStorageBytes(1024);
        assertThat(quotas.isAnyQuotaExceeded()).isTrue();
    }

    @Test
    void current_values_tracked() {
        var config = NamespaceConfig.unlimited("test");
        var quotas = new NamespaceQuotas(config);

        quotas.updateMemoryCount(42);
        quotas.updatePartitionCount(3);
        quotas.updateStorageBytes(8192);

        assertThat(quotas.currentMemoryCount()).isEqualTo(42);
        assertThat(quotas.currentPartitionCount()).isEqualTo(3);
        assertThat(quotas.currentStorageBytes()).isEqualTo(8192);
    }
}
