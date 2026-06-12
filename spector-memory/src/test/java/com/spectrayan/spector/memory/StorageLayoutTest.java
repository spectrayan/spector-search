/*
 * Copyright 2026 Spectrayan
 */
package com.spectrayan.spector.memory;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StorageLayout} — directory resolvers, partition naming,
 * WAL file naming, and legacy path resolvers.
 */
@DisplayName("StorageLayout")
class StorageLayoutTest {

    private static final Path BASE = Path.of("/data/spector");

    // ══════════════════════════════════════════════════════════════
    // Top-level directories
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("directory resolvers")
    class DirectoryTests {

        @Test void globalDir() {
            assertThat(StorageLayout.globalDir(BASE)).isEqualTo(BASE.resolve("global"));
        }

        @Test void partitionsDir() {
            assertThat(StorageLayout.partitionsDir(BASE)).isEqualTo(BASE.resolve("partitions"));
        }

        @Test void crossDir() {
            assertThat(StorageLayout.crossDir(BASE)).isEqualTo(BASE.resolve("cross"));
        }

        @Test void walDir() {
            assertThat(StorageLayout.walDir(BASE)).isEqualTo(BASE.resolve("global").resolve("wal"));
        }

        @Test void namespacesDir() {
            assertThat(StorageLayout.namespacesDir(BASE)).isEqualTo(BASE.resolve("namespaces"));
        }

        @Test void namespaceDir() {
            assertThat(StorageLayout.namespaceDir(BASE, "agent-alpha"))
                    .isEqualTo(BASE.resolve("namespaces").resolve("agent-alpha"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Global file resolvers
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("global file resolvers")
    class GlobalFileTests {

        @Test void manifest() {
            assertThat(StorageLayout.manifest(BASE)).isEqualTo(BASE.resolve("manifest.json"));
        }

        @Test void workingMem() {
            assertThat(StorageLayout.workingMem(BASE)).isEqualTo(BASE.resolve("global/working.mem"));
        }

        @Test void coactivationTracker() {
            assertThat(StorageLayout.coactivationTracker(BASE)).isEqualTo(BASE.resolve("global/coactivation.tracker"));
        }

        @Test void checkpointMeta() {
            assertThat(StorageLayout.checkpointMeta(BASE)).isEqualTo(BASE.resolve("global/checkpoint.meta"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Partition naming
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("partition naming")
    class PartitionNamingTests {

        @Test void dirName() {
            assertThat(StorageLayout.partitionDirName(0, 1717430400))
                    .isEqualTo("000_1717430400");
        }

        @Test void dirNameHighSeq() {
            assertThat(StorageLayout.partitionDirName(42, 1717430400))
                    .isEqualTo("042_1717430400");
        }

        @Test void partitionDir() {
            assertThat(StorageLayout.partitionDir(BASE, 3, 1717603200))
                    .isEqualTo(BASE.resolve("partitions/003_1717603200"));
        }

        @Test void parseSeqNo() {
            assertThat(StorageLayout.parsePartitionSeqNo("003_1717603200")).isEqualTo(3);
        }

        @Test void parseEpoch() {
            assertThat(StorageLayout.parsePartitionEpoch("003_1717603200")).isEqualTo(1717603200L);
        }

        @Test void isPartitionDir_valid() {
            assertThat(StorageLayout.isPartitionDir("000_1717430400")).isTrue();
            assertThat(StorageLayout.isPartitionDir("042_9999999999")).isTrue();
        }

        @Test void isPartitionDir_invalid() {
            assertThat(StorageLayout.isPartitionDir(null)).isFalse();
            assertThat(StorageLayout.isPartitionDir("")).isFalse();
            assertThat(StorageLayout.isPartitionDir("abc")).isFalse();
            assertThat(StorageLayout.isPartitionDir("000-1717430400")).isFalse(); // wrong separator
            assertThat(StorageLayout.isPartitionDir("0_1")).isFalse(); // too short seq
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Partition file resolvers
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("partition file resolvers")
    class PartitionFileTests {

        private final Path partDir = Path.of("/data/spector/partitions/000_1717430400");

        @Test void semanticMem()   { assertThat(StorageLayout.semanticMem(partDir).getFileName().toString()).isEqualTo("semantic.mem"); }
        @Test void episodicMem()   { assertThat(StorageLayout.episodicMem(partDir).getFileName().toString()).isEqualTo("episodic.mem"); }
        @Test void proceduralMem() { assertThat(StorageLayout.proceduralMem(partDir).getFileName().toString()).isEqualTo("procedural.mem"); }
        @Test void textDat()       { assertThat(StorageLayout.textDat(partDir).getFileName().toString()).isEqualTo("text.dat"); }
        @Test void indexMidx()     { assertThat(StorageLayout.indexMidx(partDir).getFileName().toString()).isEqualTo("index.midx"); }
        @Test void hebbianGraph()  { assertThat(StorageLayout.hebbianGraph(partDir).getFileName().toString()).isEqualTo("hebbian.graph"); }
        @Test void temporalChain() { assertThat(StorageLayout.temporalChain(partDir).getFileName().toString()).isEqualTo("temporal.chain"); }
        @Test void entityGraph()   { assertThat(StorageLayout.entityGraph(partDir).getFileName().toString()).isEqualTo("entity.graph"); }
    }

    // ══════════════════════════════════════════════════════════════
    // Cross-partition file resolvers
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cross-partition files")
    class CrossPartitionTests {

        @Test void hebbianCrossGraph() {
            assertThat(StorageLayout.hebbianCrossGraph(BASE))
                    .isEqualTo(BASE.resolve("cross/hebbian-cross.graph"));
        }

        @Test void entityCrossGraph() {
            assertThat(StorageLayout.entityCrossGraph(BASE))
                    .isEqualTo(BASE.resolve("cross/entity-cross.graph"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // WAL file resolvers
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WAL files")
    class WalTests {

        @Test void walFileName() {
            assertThat(StorageLayout.walFileName(1)).isEqualTo("wal-000001.bin");
            assertThat(StorageLayout.walFileName(42)).isEqualTo("wal-000042.bin");
        }

        @Test void walFile() {
            assertThat(StorageLayout.walFile(BASE, 1))
                    .isEqualTo(BASE.resolve("global/wal/wal-000001.bin"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Legacy resolvers
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("legacy path resolvers")
    class LegacyTests {

        @Test void legacySemanticDir() {
            assertThat(StorageLayout.legacySemanticDir(BASE)).isEqualTo(BASE.resolve("semantic"));
        }

        @Test void legacyEpisodicDir() {
            assertThat(StorageLayout.legacyEpisodicDir(BASE)).isEqualTo(BASE.resolve("episodic"));
        }

        @Test void legacyIndex() {
            assertThat(StorageLayout.legacyIndex(BASE)).isEqualTo(BASE.resolve("memory-index.mem"));
        }

        @Test void legacyProcedural() {
            assertThat(StorageLayout.legacyProcedural(BASE)).isEqualTo(BASE.resolve("procedural.mem"));
        }

        @Test void legacySemantic() {
            assertThat(StorageLayout.legacySemantic(BASE)).isEqualTo(BASE.resolve("semantic.mem"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Constants
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("magic numbers are non-zero")
    void magicNumbers() {
        assertThat(StorageLayout.TEXT_DAT_MAGIC).isNotZero();
        assertThat(StorageLayout.INDEX_MIDX_MAGIC).isNotZero();
    }

    @Test
    @DisplayName("partition pattern matches valid names")
    void partitionPattern() {
        var matcher = StorageLayout.PARTITION_DIR_PATTERN.matcher("042_1717430400");
        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.group(1)).isEqualTo("042");
        assertThat(matcher.group(2)).isEqualTo("1717430400");
    }
}
