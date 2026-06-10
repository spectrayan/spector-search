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
package com.spectrayan.spector.memory.cortex;

import com.spectrayan.spector.memory.model.MemoryType;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout;
import com.spectrayan.spector.memory.synapse.CognitiveRecordLayout.CognitiveHeader;

import java.lang.foreign.MemorySegment;
import java.util.EnumMap;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Tier store registry and polymorphic routing — zero switch statements.
 *
 * <h3>Design Pattern: Strategy + Registry</h3>
 * <p>Holds a {@code EnumMap<MemoryType, TierStore>} and dispatches all operations
 * polymorphically via the {@link TierStore} interface. Adding a new memory tier
 * (e.g., FLASH) requires: (1) implement {@link TierStore}, (2) register here.
 * Zero changes to SpectorMemory, RecallPipeline, or IngestionPipeline.</p>
 *
 * <h3>SOLID Compliance</h3>
 * <ul>
 *   <li><b>OCP</b>: Open for extension (new tiers), closed for modification</li>
 *   <li><b>DIP</b>: Depends on {@link TierStore} abstraction, not concrete stores</li>
 *   <li><b>LSP</b>: All stores are substitutable via the common interface</li>
 * </ul>
 */
public final class TierRouter implements AutoCloseable {

    private final EnumMap<MemoryType, TierStore> stores = new EnumMap<>(MemoryType.class);

    // ── Typed accessors for tier-specific operations ──
    private final WorkingMemoryStore workingStore;
    private final EpisodicMemoryStore episodicStore;
    private final SemanticMemoryStore semanticStore;
    private final ProceduralMemoryStore proceduralStore;

    /**
     * Creates a TierRouter with all four cognitive tier stores.
     *
     * <p>Each store is registered in the internal {@code EnumMap} for polymorphic
     * dispatch, while typed fields are retained for tier-specific operations
     * (e.g., episodic partition iteration, semantic header reads).</p>
     */
    public TierRouter(WorkingMemoryStore workingStore,
                       EpisodicMemoryStore episodicStore,
                       SemanticMemoryStore semanticStore,
                       ProceduralMemoryStore proceduralStore) {
        this.workingStore = workingStore;
        this.episodicStore = episodicStore;
        this.semanticStore = semanticStore;
        this.proceduralStore = proceduralStore;

        // Register in EnumMap for polymorphic dispatch
        stores.put(MemoryType.WORKING, workingStore);
        stores.put(MemoryType.EPISODIC, episodicStore);
        stores.put(MemoryType.SEMANTIC, semanticStore);
        stores.put(MemoryType.PROCEDURAL, proceduralStore);
    }

    // ══════════════════════════════════════════════════════════════
    // POLYMORPHIC DISPATCH (zero switch statements)
    // ══════════════════════════════════════════════════════════════

    /**
     * Returns the {@link TierStore} for a given memory type.
     *
     * @throws SpectorValidationException if no store is registered for the type
     */
    public TierStore get(MemoryType type) {
        TierStore store = stores.get(type);
        if (store == null) {
            throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "storeType", type);
        }
        return store;
    }

    /**
     * Routes a memory write to the appropriate tier store.
     * Polymorphic — delegates to {@link TierStore#write}.
     *
     * @param type       target memory tier
     * @param header     cognitive header
     * @param quantized  quantized vector bytes
     * @return byte offset where the record was written
     */
    public long write(MemoryType type, CognitiveHeader header, byte[] quantized) {
        return get(type).write(header, quantized);
    }

    /**
     * Returns the primary memory segment for a given tier.
     * Polymorphic — delegates to {@link TierStore#primarySegment}.
     */
    public MemorySegment segmentFor(MemoryType type) {
        return get(type).primarySegment();
    }

    /**
     * Returns the layout for a given tier.
     * Polymorphic — delegates to {@link TierStore#layout}.
     */
    public CognitiveRecordLayout layoutFor(MemoryType type) {
        return get(type).layout();
    }

    /**
     * Returns the record count for a given tier.
     * Polymorphic — delegates to {@link TierStore#size}.
     */
    public int countFor(MemoryType type) {
        return get(type).size();
    }

    /**
     * Returns the total memory count across all registered tiers.
     */
    public int totalCount() {
        int total = 0;
        for (TierStore store : stores.values()) {
            total += store.size();
        }
        return total;
    }

    /**
     * Checks if a given memory type should be scanned based on the target type filter.
     *
     * @param type        the tier to check
     * @param targetTypes target type filter (null or empty = scan all)
     * @return true if this type should be scanned
     */
    public static boolean shouldScan(MemoryType type, MemoryType[] targetTypes) {
        if (targetTypes == null || targetTypes.length == 0) return true;
        for (MemoryType t : targetTypes) {
            if (t == type) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // TYPED ACCESSORS (for tier-specific operations)
    // ══════════════════════════════════════════════════════════════

    /** Returns the Working Memory store (for circular buffer scan). */
    public WorkingMemoryStore working() { return workingStore; }

    /** Returns the Episodic Memory store (for partition iteration). */
    public EpisodicMemoryStore episodic() { return episodicStore; }

    /** Returns the Semantic Memory store (for header slab access). */
    public SemanticMemoryStore semantic() { return semanticStore; }

    /** Returns the Procedural Memory store (for flat scan). */
    public ProceduralMemoryStore procedural() { return proceduralStore; }

    /**
     * Forces all persistent tier store segments to be written to disk.
     * Used by {@code CheckpointDaemon} before recording a WAL checkpoint.
     */
    public void forceAll() {
        for (TierStore store : stores.values()) {
            if (store instanceof AbstractTierStore ats && ats.isPersistent()) {
                ats.force();
            }
        }
    }


    @Override
    public void close() {
        stores.values().forEach(store -> {
            try {
                store.close();
            } catch (Exception e) {
                // Log and continue closing remaining stores
            }
        });
    }
}
