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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Off-heap entity-relationship graph for multi-hop knowledge traversal.
 *
 * <h3>Biological Analog: Semantic Network</h3>
 * <p>The brain's semantic memory stores knowledge as a network of concepts
 * connected by typed relationships. "Alice manages Project Alpha" is stored
 * as: [Alice]—MANAGES→[Project Alpha]. This graph enables multi-hop reasoning:
 * "Find memories about projects managed by the person I met yesterday."</p>
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Off-heap entity nodes backed by {@link MemorySegment}</li>
 *   <li>Off-heap typed edges with fixed-width adjacency</li>
 *   <li>On-heap name→id index for O(1) entity lookup (case-insensitive)</li>
 *   <li>Max 32 edges per entity, max 4 memory references per entity</li>
 *   <li>Persistence via save/load with "EGPH" magic header</li>
 * </ul>
 *
 * <h3>Layout</h3>
 * <pre>
 *   Entity Node (64 bytes, 8-byte aligned):
 *     [type:1B][pad:7B][nameHash:8B][memRef0:4B][memRef1:4B][memRef2:4B][memRef3:4B]
 *     [refCount:4B][degree:4B][edgeStart:4B][pad:20B]
 *
 *   Entity Edge (12 bytes):
 *     [targetId:4B][relationType:4B][weight:4B]
 * </pre>
 */
public final class EntityGraph implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EntityGraph.class);

    /** File magic: "EGPH" in ASCII. */
    private static final int FILE_MAGIC = 0x45475048;
    private static final int FILE_VERSION = 1;
    private static final int FILE_HEADER_BYTES = 24; // magic + version + entityCap + edgeCap + entityCount + reserved

    /** Maximum memory references per entity. */
    public static final int MAX_MEMORY_REFS = 4;

    /** Maximum edges per entity. */
    public static final int MAX_DEGREE = 32;

    // ── Entity Node Layout (64 bytes, 8-byte aligned) ──
    static final int ENTITY_NODE_BYTES = 64;
    private static final long ENT_OFF_TYPE = 0;         // 1B
    // pad: 7B for alignment
    private static final long ENT_OFF_NAME_HASH = 8;    // 8B (8-byte aligned)
    private static final long ENT_OFF_MEM_REFS = 16;    // 4 × 4B = 16B
    private static final long ENT_OFF_REF_COUNT = 32;   // 4B
    private static final long ENT_OFF_DEGREE = 36;      // 4B
    private static final long ENT_OFF_EDGE_START = 40;  // 4B (index into edge segment)
    // pad: 20B to reach 64B

    // ── Entity Edge Layout (12 bytes) ──
    static final int EDGE_BYTES = 12;
    private static final long EDGE_OFF_TARGET = 0;       // 4B
    private static final long EDGE_OFF_REL_TYPE = 4;     // 4B
    private static final long EDGE_OFF_WEIGHT = 8;       // 4B (float)

    private final Arena arena;
    private final MemorySegment entitySegment;
    private final MemorySegment edgeSegment;
    private final int entityCapacity;
    private final int edgeCapacity;
    private int entityCount;
    private int edgeCount;

    /** On-heap name→entityId index for O(1) lookup (case-insensitive). */
    private final ConcurrentHashMap<String, Integer> nameIndex = new ConcurrentHashMap<>();

    /**
     * Creates a new entity graph.
     *
     * @param entityCapacity maximum number of entities
     * @param edgeCapacity   maximum number of edges (default: entityCapacity × MAX_DEGREE)
     */
    public EntityGraph(int entityCapacity, int edgeCapacity) {
        this.entityCapacity = entityCapacity;
        this.edgeCapacity = edgeCapacity;
        this.entityCount = 0;
        this.edgeCount = 0;
        this.arena = Arena.ofShared();
        this.entitySegment = arena.allocate((long) ENTITY_NODE_BYTES * entityCapacity);
        this.edgeSegment = arena.allocate((long) EDGE_BYTES * edgeCapacity);
        entitySegment.fill((byte) 0);
        edgeSegment.fill((byte) 0);

        log.info("EntityGraph initialized: entities={}, edges={}, memory={}KB",
                entityCapacity, edgeCapacity,
                ((long) ENTITY_NODE_BYTES * entityCapacity + (long) EDGE_BYTES * edgeCapacity) / 1024);
    }

    /**
     * Creates a new entity graph with default edge capacity.
     *
     * @param entityCapacity maximum number of entities
     */
    public EntityGraph(int entityCapacity) {
        this(entityCapacity, entityCapacity * MAX_DEGREE);
    }

    /**
     * Private constructor for loading from pre-existing segments.
     */
    private EntityGraph(int entityCapacity, int edgeCapacity, int entityCount, int edgeCount,
                         Arena arena, MemorySegment entitySegment, MemorySegment edgeSegment,
                         ConcurrentHashMap<String, Integer> nameIndex) {
        this.entityCapacity = entityCapacity;
        this.edgeCapacity = edgeCapacity;
        this.entityCount = entityCount;
        this.edgeCount = edgeCount;
        this.arena = arena;
        this.entitySegment = entitySegment;
        this.edgeSegment = edgeSegment;
        this.nameIndex.putAll(nameIndex);
    }

    /**
     * Adds an entity to the graph, or returns the existing ID if already present.
     *
     * <p>Entity names are case-insensitive and normalized to lowercase.</p>
     *
     * @param name entity name
     * @param type entity type
     * @return entity ID (index into entity segment)
     */
    public int addEntity(String name, EntityType type) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);

        // Check if already exists
        Integer existing = nameIndex.get(normalized);
        if (existing != null) {
            return existing;
        }

        if (entityCount >= entityCapacity) {
            log.warn("EntityGraph full ({} entities), rejecting '{}'", entityCapacity, name);
            return -1;
        }

        int entityId = entityCount++;
        long offset = (long) entityId * ENTITY_NODE_BYTES;

        entitySegment.set(ValueLayout.JAVA_BYTE, offset + ENT_OFF_TYPE, (byte) type.ordinal());
        entitySegment.set(ValueLayout.JAVA_LONG, offset + ENT_OFF_NAME_HASH, normalized.hashCode());
        entitySegment.set(ValueLayout.JAVA_INT, offset + ENT_OFF_REF_COUNT, 0);
        entitySegment.set(ValueLayout.JAVA_INT, offset + ENT_OFF_DEGREE, 0);
        entitySegment.set(ValueLayout.JAVA_INT, offset + ENT_OFF_EDGE_START, -1);

        nameIndex.put(normalized, entityId);

        log.trace("Entity added: id={}, name='{}', type={}", entityId, name, type);
        return entityId;
    }

    /**
     * Adds a typed relation between two entities.
     *
     * @param fromEntity source entity ID
     * @param toEntity   target entity ID
     * @param type       relation type
     */
    public synchronized void addRelation(int fromEntity, int toEntity, RelationType type) {
        if (fromEntity < 0 || fromEntity >= entityCount) return;
        if (toEntity < 0 || toEntity >= entityCount) return;
        if (fromEntity == toEntity) return;

        long entityOffset = (long) fromEntity * ENTITY_NODE_BYTES;
        int degree = entitySegment.get(ValueLayout.JAVA_INT, entityOffset + ENT_OFF_DEGREE);
        int edgeStart = entitySegment.get(ValueLayout.JAVA_INT, entityOffset + ENT_OFF_EDGE_START);

        // Check if relation already exists (strengthen weight)
        if (edgeStart >= 0) {
            for (int i = 0; i < degree; i++) {
                long edgeOffset = (long) (edgeStart + i) * EDGE_BYTES;
                int target = edgeSegment.get(ValueLayout.JAVA_INT, edgeOffset + EDGE_OFF_TARGET);
                int relType = edgeSegment.get(ValueLayout.JAVA_INT, edgeOffset + EDGE_OFF_REL_TYPE);
                if (target == toEntity && relType == type.ordinal()) {
                    // Strengthen existing edge
                    float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, edgeOffset + EDGE_OFF_WEIGHT);
                    edgeSegment.set(ValueLayout.JAVA_FLOAT, edgeOffset + EDGE_OFF_WEIGHT, weight + 1.0f);
                    return;
                }
            }
        }

        // Add new edge
        if (degree >= MAX_DEGREE) {
            log.trace("Entity {} at max degree ({}), rejecting edge to {}", fromEntity, MAX_DEGREE, toEntity);
            return;
        }
        if (edgeCount >= edgeCapacity) {
            log.warn("EntityGraph edge capacity full ({}), rejecting edge", edgeCapacity);
            return;
        }

        // Allocate edge block if first edge for this entity
        if (edgeStart < 0) {
            edgeStart = edgeCount;
            entitySegment.set(ValueLayout.JAVA_INT, entityOffset + ENT_OFF_EDGE_START, edgeStart);
        }

        int edgeIdx = edgeStart + degree;
        // If non-contiguous, append at end
        if (edgeIdx != edgeCount && edgeStart + degree >= edgeCount) {
            edgeIdx = edgeCount;
        }

        long edgeOffset = (long) edgeIdx * EDGE_BYTES;
        edgeSegment.set(ValueLayout.JAVA_INT, edgeOffset + EDGE_OFF_TARGET, toEntity);
        edgeSegment.set(ValueLayout.JAVA_INT, edgeOffset + EDGE_OFF_REL_TYPE, type.ordinal());
        edgeSegment.set(ValueLayout.JAVA_FLOAT, edgeOffset + EDGE_OFF_WEIGHT, 1.0f);

        entitySegment.set(ValueLayout.JAVA_INT, entityOffset + ENT_OFF_DEGREE, degree + 1);
        edgeCount = Math.max(edgeCount, edgeIdx + 1);
    }

    /**
     * Links an entity to a memory index.
     *
     * @param entityId  entity ID
     * @param memoryIdx index of the memory that mentions this entity
     */
    public void linkEntityToMemory(int entityId, int memoryIdx) {
        if (entityId < 0 || entityId >= entityCount) return;

        long offset = (long) entityId * ENTITY_NODE_BYTES;
        int refCount = entitySegment.get(ValueLayout.JAVA_INT, offset + ENT_OFF_REF_COUNT);

        if (refCount >= MAX_MEMORY_REFS) return; // full

        // Check for duplicate
        for (int i = 0; i < refCount; i++) {
            int existing = entitySegment.get(ValueLayout.JAVA_INT,
                    offset + ENT_OFF_MEM_REFS + (long) i * 4);
            if (existing == memoryIdx) return;
        }

        entitySegment.set(ValueLayout.JAVA_INT,
                offset + ENT_OFF_MEM_REFS + (long) refCount * 4, memoryIdx);
        entitySegment.set(ValueLayout.JAVA_INT, offset + ENT_OFF_REF_COUNT, refCount + 1);
    }

    /**
     * Finds an entity by name (case-insensitive).
     *
     * @param name entity name
     * @return entity ID, or -1 if not found
     */
    public int findEntity(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        Integer id = nameIndex.get(normalized);
        return id != null ? id : -1;
    }

    /**
     * Returns the memory indices that reference an entity.
     *
     * @param entityId entity ID
     * @return array of memory indices
     */
    public int[] memoriesForEntity(int entityId) {
        if (entityId < 0 || entityId >= entityCount) return new int[0];

        long offset = (long) entityId * ENTITY_NODE_BYTES;
        int refCount = entitySegment.get(ValueLayout.JAVA_INT, offset + ENT_OFF_REF_COUNT);
        int[] result = new int[refCount];
        for (int i = 0; i < refCount; i++) {
            result[i] = entitySegment.get(ValueLayout.JAVA_INT,
                    offset + ENT_OFF_MEM_REFS + (long) i * 4);
        }
        return result;
    }

    /**
     * Returns the entity type for an entity ID.
     */
    public EntityType entityType(int entityId) {
        if (entityId < 0 || entityId >= entityCount) return EntityType.OTHER;
        byte typeOrd = entitySegment.get(ValueLayout.JAVA_BYTE,
                (long) entityId * ENTITY_NODE_BYTES + ENT_OFF_TYPE);
        EntityType[] types = EntityType.values();
        return typeOrd >= 0 && typeOrd < types.length ? types[typeOrd] : EntityType.OTHER;
    }

    /**
     * Returns the edges for an entity.
     */
    public List<EntityEdge> edges(int entityId) {
        if (entityId < 0 || entityId >= entityCount) return List.of();

        long offset = (long) entityId * ENTITY_NODE_BYTES;
        int degree = entitySegment.get(ValueLayout.JAVA_INT, offset + ENT_OFF_DEGREE);
        int edgeStart = entitySegment.get(ValueLayout.JAVA_INT, offset + ENT_OFF_EDGE_START);

        if (edgeStart < 0 || degree == 0) return List.of();

        List<EntityEdge> result = new ArrayList<>(degree);
        for (int i = 0; i < degree; i++) {
            long edgeOffset = (long) (edgeStart + i) * EDGE_BYTES;
            int target = edgeSegment.get(ValueLayout.JAVA_INT, edgeOffset + EDGE_OFF_TARGET);
            int relTypeOrd = edgeSegment.get(ValueLayout.JAVA_INT, edgeOffset + EDGE_OFF_REL_TYPE);
            float weight = edgeSegment.get(ValueLayout.JAVA_FLOAT, edgeOffset + EDGE_OFF_WEIGHT);

            RelationType[] types = RelationType.values();
            RelationType relType = relTypeOrd >= 0 && relTypeOrd < types.length
                    ? types[relTypeOrd] : RelationType.OTHER;

            result.add(new EntityEdge(target, relType, weight));
        }
        return result;
    }

    /**
     * BFS traversal from a starting entity with optional relation type filter.
     *
     * @param startEntity entity ID to start from
     * @param filter      relation type filter (null = accept all)
     * @param maxHops     maximum traversal depth
     * @return list of reached entity IDs with their hop distances
     */
    public List<TraversalResult> traverse(int startEntity, RelationType filter, int maxHops) {
        if (startEntity < 0 || startEntity >= entityCount) return List.of();

        List<TraversalResult> results = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Queue<int[]> queue = new LinkedList<>(); // [entityId, depth]
        queue.add(new int[]{startEntity, 0});
        visited.add(startEntity);

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int entityId = current[0];
            int depth = current[1];

            if (depth > 0) {
                results.add(new TraversalResult(entityId, depth));
            }

            if (depth >= maxHops) continue;

            for (EntityEdge edge : edges(entityId)) {
                if (filter != null && edge.relationType() != filter) continue;
                if (visited.contains(edge.targetEntityId())) continue;
                visited.add(edge.targetEntityId());
                queue.add(new int[]{edge.targetEntityId(), depth + 1});
            }
        }

        return results;
    }

    /**
     * Collects all memory indices reachable from a starting entity within maxHops.
     *
     * @param startEntity starting entity ID
     * @param filter      optional relation type filter
     * @param maxHops     maximum traversal depth
     * @return set of memory indices
     */
    public Set<Integer> collectMemories(int startEntity, RelationType filter, int maxHops) {
        Set<Integer> memories = new HashSet<>();

        // Include start entity's memories
        for (int memIdx : memoriesForEntity(startEntity)) {
            memories.add(memIdx);
        }

        // Traverse and collect
        for (TraversalResult tr : traverse(startEntity, filter, maxHops)) {
            for (int memIdx : memoriesForEntity(tr.entityId())) {
                memories.add(memIdx);
            }
        }

        return memories;
    }

    /**
     * Returns the number of entities in the graph.
     */
    public int entityCount() {
        return entityCount;
    }

    /**
     * Returns the number of edges in the graph.
     */
    public int edgeCount() {
        return edgeCount;
    }

    /**
     * Returns the name index for inspection/debugging.
     */
    public Map<String, Integer> nameIndex() {
        return Map.copyOf(nameIndex);
    }

    /**
     * An edge in the entity graph.
     */
    public record EntityEdge(int targetEntityId, RelationType relationType, float weight) {}

    /**
     * A BFS traversal result.
     */
    public record TraversalResult(int entityId, int hopDistance) {}

    // ══════════════════════════════════════════════════════════════
    // PERSISTENCE: save / load
    // ══════════════════════════════════════════════════════════════

    /**
     * Saves the graph to a binary file.
     *
     * @param filePath path to write
     */
    public void save(Path filePath) {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create graph directory: " + parent, e);
            }
        }

        try (FileChannel ch = FileChannel.open(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // Header: magic + version + entityCap + edgeCap + entityCount + edgeCount
            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            header.putInt(FILE_MAGIC);
            header.putInt(FILE_VERSION);
            header.putInt(entityCapacity);
            header.putInt(edgeCapacity);
            header.putInt(entityCount);
            header.putInt(edgeCount);
            header.flip();
            ch.write(header);

            // Write entity segment
            writeSegment(ch, entitySegment, (long) ENTITY_NODE_BYTES * entityCapacity);

            // Write edge segment
            writeSegment(ch, edgeSegment, (long) EDGE_BYTES * edgeCapacity);

            // Write name index (on-heap → serialized)
            ByteBuffer nameCountBuf = ByteBuffer.allocate(4);
            nameCountBuf.putInt(nameIndex.size());
            nameCountBuf.flip();
            ch.write(nameCountBuf);

            for (Map.Entry<String, Integer> entry : nameIndex.entrySet()) {
                byte[] nameBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ByteBuffer entryBuf = ByteBuffer.allocate(4 + nameBytes.length + 4);
                entryBuf.putInt(nameBytes.length);
                entryBuf.put(nameBytes);
                entryBuf.putInt(entry.getValue());
                entryBuf.flip();
                ch.write(entryBuf);
            }

            ch.force(true);
            log.info("EntityGraph saved: entities={}, edges={} → {}",
                    entityCount, edgeCount, filePath);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save EntityGraph: " + filePath, e);
        }
    }

    /**
     * Loads a graph from a binary file, or returns a new empty graph.
     *
     * @param filePath          path to the graph file
     * @param defaultEntityCap  entity capacity if file doesn't exist
     * @param defaultEdgeCap    edge capacity if file doesn't exist
     * @return an EntityGraph (loaded or new)
     */
    public static EntityGraph load(Path filePath, int defaultEntityCap, int defaultEdgeCap) {
        if (filePath == null || !Files.exists(filePath)) {
            log.info("EntityGraph file not found, creating fresh: {}", filePath);
            return new EntityGraph(defaultEntityCap, defaultEdgeCap);
        }

        try (FileChannel ch = FileChannel.open(filePath, StandardOpenOption.READ)) {
            if (ch.size() < FILE_HEADER_BYTES) {
                log.warn("EntityGraph file too small, creating fresh");
                return new EntityGraph(defaultEntityCap, defaultEdgeCap);
            }

            ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_BYTES);
            ch.read(header);
            header.flip();

            int magic = header.getInt();
            int version = header.getInt();
            int entityCap = header.getInt();
            int edgeCap = header.getInt();
            int entCount = header.getInt();
            int edgCount = header.getInt();

            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                log.warn("Invalid EntityGraph file, creating fresh");
                return new EntityGraph(defaultEntityCap, defaultEdgeCap);
            }

            Arena arena = Arena.ofShared();

            // Read entity segment
            long entityBytes = (long) ENTITY_NODE_BYTES * entityCap;
            MemorySegment entSeg = arena.allocate(entityBytes);
            readSegment(ch, entSeg, entityBytes);

            // Read edge segment
            long edgeBytes = (long) EDGE_BYTES * edgeCap;
            MemorySegment edgSeg = arena.allocate(edgeBytes);
            readSegment(ch, edgSeg, edgeBytes);

            // Read name index
            ConcurrentHashMap<String, Integer> names = new ConcurrentHashMap<>();
            ByteBuffer countBuf = ByteBuffer.allocate(4);
            ch.read(countBuf);
            countBuf.flip();
            int nameCount = countBuf.getInt();

            for (int i = 0; i < nameCount; i++) {
                ByteBuffer lenBuf = ByteBuffer.allocate(4);
                ch.read(lenBuf);
                lenBuf.flip();
                int len = lenBuf.getInt();

                ByteBuffer nameBuf = ByteBuffer.allocate(len);
                ch.read(nameBuf);
                nameBuf.flip();
                String name = new String(nameBuf.array(), 0, len, java.nio.charset.StandardCharsets.UTF_8);

                ByteBuffer idBuf = ByteBuffer.allocate(4);
                ch.read(idBuf);
                idBuf.flip();
                int id = idBuf.getInt();

                names.put(name, id);
            }

            EntityGraph graph = new EntityGraph(entityCap, edgeCap, entCount, edgCount,
                    arena, entSeg, edgSeg, names);
            log.info("EntityGraph loaded: entities={}, edges={} from {}",
                    entCount, edgCount, filePath);
            return graph;

        } catch (IOException e) {
            log.error("Failed to load EntityGraph, creating fresh: {}", e.getMessage());
            return new EntityGraph(defaultEntityCap, defaultEdgeCap);
        }
    }

    private static void writeSegment(FileChannel ch, MemorySegment seg, long totalBytes)
            throws IOException {
        long written = 0;
        int chunkSize = 64 * 1024;
        while (written < totalBytes) {
            int toWrite = (int) Math.min(chunkSize, totalBytes - written);
            ByteBuffer buf = seg.asSlice(written, toWrite).asByteBuffer().asReadOnlyBuffer();
            ch.write(buf);
            written += toWrite;
        }
    }

    private static void readSegment(FileChannel ch, MemorySegment seg, long totalBytes)
            throws IOException {
        long read = 0;
        int chunkSize = 64 * 1024;
        while (read < totalBytes) {
            int toRead = (int) Math.min(chunkSize, totalBytes - read);
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            ch.read(buf);
            buf.flip();
            MemorySegment.copy(MemorySegment.ofBuffer(buf), 0, seg, read, toRead);
            read += toRead;
        }
    }

    @Override
    public void close() {
        log.info("EntityGraph closing (entities={}, edges={})", entityCount, edgeCount);
        arena.close();
    }
}
