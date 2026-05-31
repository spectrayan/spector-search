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
package com.spectrayan.spector.memory.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EntityGraph: entity management, relations, traversal, and persistence.
 */
class EntityGraphTest {

    @TempDir
    Path tempDir;

    private EntityGraph graph;

    @BeforeEach
    void setUp() {
        graph = new EntityGraph(100, 500);
    }

    @AfterEach
    void tearDown() {
        graph.close();
    }

    @Test
    void addEntityReturnsId() {
        int id = graph.addEntity("Alice", EntityType.PERSON);
        assertThat(id).isEqualTo(0);
        assertThat(graph.entityCount()).isEqualTo(1);
    }

    @Test
    void addDuplicateEntityReturnsExistingId() {
        int id1 = graph.addEntity("Alice", EntityType.PERSON);
        int id2 = graph.addEntity("alice", EntityType.PERSON); // case-insensitive
        int id3 = graph.addEntity("ALICE", EntityType.PERSON);

        assertThat(id1).isEqualTo(id2).isEqualTo(id3);
        assertThat(graph.entityCount()).isEqualTo(1);
    }

    @Test
    void findEntityCaseInsensitive() {
        graph.addEntity("Project Alpha", EntityType.PROJECT);

        assertThat(graph.findEntity("project alpha")).isEqualTo(0);
        assertThat(graph.findEntity("PROJECT ALPHA")).isEqualTo(0);
        assertThat(graph.findEntity("nonexistent")).isEqualTo(-1);
    }

    @Test
    void entityTypePreserved() {
        graph.addEntity("Alice", EntityType.PERSON);
        graph.addEntity("Acme", EntityType.ORGANIZATION);

        assertThat(graph.entityType(0)).isEqualTo(EntityType.PERSON);
        assertThat(graph.entityType(1)).isEqualTo(EntityType.ORGANIZATION);
    }

    @Test
    void addRelation() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);
        int project = graph.addEntity("Project Alpha", EntityType.PROJECT);

        graph.addRelation(alice, project, RelationType.MANAGES);

        List<EntityGraph.EntityEdge> edges = graph.edges(alice);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).targetEntityId()).isEqualTo(project);
        assertThat(edges.get(0).relationType()).isEqualTo(RelationType.MANAGES);
        assertThat(edges.get(0).weight()).isEqualTo(1.0f);
    }

    @Test
    void duplicateRelationStrengthensWeight() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);
        int project = graph.addEntity("Project Alpha", EntityType.PROJECT);

        graph.addRelation(alice, project, RelationType.MANAGES);
        graph.addRelation(alice, project, RelationType.MANAGES);

        List<EntityGraph.EntityEdge> edges = graph.edges(alice);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).weight()).isEqualTo(2.0f);
    }

    @Test
    void linkEntityToMemory() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);

        graph.linkEntityToMemory(alice, 42);
        graph.linkEntityToMemory(alice, 99);
        graph.linkEntityToMemory(alice, 42); // duplicate: ignored

        int[] memories = graph.memoriesForEntity(alice);
        assertThat(memories).containsExactly(42, 99);
    }

    @Test
    void maxMemoryRefsEnforced() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);

        for (int i = 0; i < EntityGraph.MAX_MEMORY_REFS + 5; i++) {
            graph.linkEntityToMemory(alice, i);
        }

        int[] memories = graph.memoriesForEntity(alice);
        assertThat(memories).hasSize(EntityGraph.MAX_MEMORY_REFS);
    }

    @Test
    void bfsTraversal() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);
        int project = graph.addEntity("Project Alpha", EntityType.PROJECT);
        int bob = graph.addEntity("Bob", EntityType.PERSON);

        graph.addRelation(alice, project, RelationType.MANAGES);
        graph.addRelation(project, bob, RelationType.PART_OF);

        // Traverse from alice: should reach project (hop 1) and bob (hop 2)
        var results = graph.traverse(alice, null, 2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).entityId()).isEqualTo(project);
        assertThat(results.get(0).hopDistance()).isEqualTo(1);
        assertThat(results.get(1).entityId()).isEqualTo(bob);
        assertThat(results.get(1).hopDistance()).isEqualTo(2);
    }

    @Test
    void bfsTraversalWithFilter() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);
        int project = graph.addEntity("Project Alpha", EntityType.PROJECT);
        int bob = graph.addEntity("Bob", EntityType.PERSON);

        graph.addRelation(alice, project, RelationType.MANAGES);
        graph.addRelation(alice, bob, RelationType.RELATED_TO);

        // Filter: only MANAGES edges
        var results = graph.traverse(alice, RelationType.MANAGES, 2);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).entityId()).isEqualTo(project);
    }

    @Test
    void collectMemories() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);
        int project = graph.addEntity("Project Alpha", EntityType.PROJECT);

        graph.linkEntityToMemory(alice, 10);
        graph.linkEntityToMemory(project, 20);
        graph.addRelation(alice, project, RelationType.MANAGES);

        Set<Integer> memories = graph.collectMemories(alice, null, 2);
        assertThat(memories).containsExactlyInAnyOrder(10, 20);
    }

    @Test
    void saveAndLoadPreservesGraph() {
        int alice = graph.addEntity("Alice", EntityType.PERSON);
        int project = graph.addEntity("Project Alpha", EntityType.PROJECT);
        graph.addRelation(alice, project, RelationType.MANAGES);
        graph.linkEntityToMemory(alice, 42);

        Path file = tempDir.resolve("test.entity");
        graph.save(file);
        graph.close();

        graph = EntityGraph.load(file, 100, 500);
        assertThat(graph.entityCount()).isEqualTo(2);
        assertThat(graph.findEntity("alice")).isEqualTo(0);
        assertThat(graph.findEntity("project alpha")).isEqualTo(1);

        // Relations preserved
        var edges = graph.edges(0);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).relationType()).isEqualTo(RelationType.MANAGES);

        // Memory refs preserved
        int[] memories = graph.memoriesForEntity(0);
        assertThat(memories).containsExactly(42);
    }

    @Test
    void loadNonExistentFileCreatesNew() {
        Path file = tempDir.resolve("nonexistent.entity");
        graph.close();
        graph = EntityGraph.load(file, 50, 200);

        assertThat(graph.entityCount()).isZero();
    }

    @Test
    void boundsCheckDoesNotCrash() {
        graph.addRelation(-1, 0, RelationType.OTHER); // ignored
        graph.addRelation(0, 500, RelationType.OTHER); // ignored
        graph.linkEntityToMemory(-1, 0); // ignored
        assertThat(graph.edges(-1)).isEmpty();
        assertThat(graph.memoriesForEntity(-1)).isEmpty();
        assertThat(graph.entityType(-1)).isEqualTo(EntityType.OTHER);
    }

    @Test
    void nameIndexSnapshot() {
        graph.addEntity("Alice", EntityType.PERSON);
        graph.addEntity("Bob", EntityType.PERSON);

        var snapshot = graph.nameIndex();
        assertThat(snapshot).containsKeys("alice", "bob");
    }
}
