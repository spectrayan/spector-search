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

import com.spectrayan.spector.commons.concurrent.ConcurrentExecutionException;
import com.spectrayan.spector.commons.concurrent.ConcurrentTasks;
import com.spectrayan.spector.index.ScoredResult;
import com.spectrayan.spector.index.SpladeIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-partition SPLADE index manager for learned sparse retrieval.
 *
 * <h3>Architecture</h3>
 * <p>Mirrors {@link MemoryBM25Index} — maintains an array of {@link SpladeIndex}
 * instances, one per memory partition. On recall, searches all partitions in parallel
 * and merges results by inner-product score. On startup, each partition's SPLADE
 * index is rebuilt from stored sparse vectors.</p>
 *
 * <h3>Relationship to BM25</h3>
 * <p>SPLADE and BM25 are complementary:
 * <ul>
 *   <li><b>BM25</b>: exact keyword matching using corpus TF-IDF statistics</li>
 *   <li><b>SPLADE</b>: learned term expansion capturing synonyms and related concepts</li>
 * </ul>
 * Both produce scored results that are fused via RRF in the recall pipeline.
 * In {@link com.spectrayan.spector.memory.model.TextSearchMode#FULL_STACK},
 * both indexes are searched in parallel.</p>
 *
 * <h3>Graceful Degradation</h3>
 * <p>If no {@link com.spectrayan.spector.embed.SparseEncodingProvider} is configured,
 * this index will be empty and all search operations return empty results.
 * The recall pipeline handles this gracefully.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Same model as {@link MemoryBM25Index}: {@link CopyOnWriteArrayList} for
 * partition list, internal locks in each {@link SpladeIndex}.</p>
 *
 * @see SpladeIndex
 * @see MemoryBM25Index
 */
public final class MemorySpladeIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemorySpladeIndex.class);

    /**
     * A SPLADE search result with its source partition.
     *
     * @param id              memory identifier
     * @param spladeScore     the inner-product SPLADE score
     * @param partitionIndex  which partition this result came from
     */
    public record SpladeCandidate(String id, float spladeScore, int partitionIndex) {}

    private final CopyOnWriteArrayList<SpladeIndex> partitions;

    /**
     * Creates an empty MemorySpladeIndex with no partitions.
     */
    public MemorySpladeIndex() {
        this.partitions = new CopyOnWriteArrayList<>();
    }

    /**
     * Creates a MemorySpladeIndex with the given number of pre-allocated empty partitions.
     *
     * @param partitionCount number of partitions to pre-allocate
     */
    public MemorySpladeIndex(int partitionCount) {
        this.partitions = new CopyOnWriteArrayList<>();
        for (int i = 0; i < partitionCount; i++) {
            partitions.add(new SpladeIndex());
        }
    }

    /**
     * Indexes a sparse vector into a specific partition's SPLADE index.
     *
     * @param partitionIndex the target partition
     * @param id             memory identifier
     * @param sparseVec      SPLADE-encoded sparse vector: term → neural weight
     */
    public void index(int partitionIndex, String id, Map<String, Float> sparseVec) {
        ensurePartition(partitionIndex);
        partitions.get(partitionIndex).indexSparse(id, sparseVec);
    }

    /**
     * Searches all partitions in parallel using a SPLADE query sparse vector.
     *
     * @param querySparse SPLADE-encoded query sparse vector
     * @param topK        maximum number of results to return
     * @return list of SPLADE candidates sorted by score descending
     */
    public List<SpladeCandidate> search(Map<String, Float> querySparse, int topK) {
        if (partitions.isEmpty()) {
            return List.of();
        }

        List<SpladeIndex> snapshot = new ArrayList<>(partitions);

        List<Callable<List<SpladeCandidate>>> tasks = new ArrayList<>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            final int partIdx = i;
            final SpladeIndex idx = snapshot.get(i);
            if (idx.size() > 0) {
                tasks.add(() -> {
                    ScoredResult[] results = idx.searchSparse(querySparse, topK);
                    List<SpladeCandidate> candidates = new ArrayList<>(results.length);
                    for (ScoredResult sr : results) {
                        candidates.add(new SpladeCandidate(sr.id(), sr.score(), partIdx));
                    }
                    return candidates;
                });
            }
        }

        if (tasks.isEmpty()) {
            return List.of();
        }

        List<SpladeCandidate> merged;
        if (tasks.size() == 1) {
            try {
                merged = tasks.getFirst().call();
            } catch (Exception e) {
                log.error("SPLADE single-partition search failed", e);
                return List.of();
            }
        } else {
            merged = new ArrayList<>();
            try {
                List<List<SpladeCandidate>> partitionResults = ConcurrentTasks.forkJoinAll(tasks);
                for (List<SpladeCandidate> pr : partitionResults) {
                    merged.addAll(pr);
                }
            } catch (ConcurrentExecutionException e) {
                log.error("Parallel SPLADE search failed, falling back to sequential", e);
                merged = searchSequential(snapshot, querySparse, topK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("SPLADE search interrupted");
                return List.of();
            }
        }

        merged.sort(Comparator.comparingDouble(SpladeCandidate::spladeScore).reversed());
        if (merged.size() > topK) {
            return merged.subList(0, topK);
        }
        return merged;
    }

    /**
     * Rebuilds a single partition's SPLADE index from stored sparse vectors.
     *
     * @param partitionIndex the partition to rebuild
     * @param sparseVecs     map of memory ID → SPLADE sparse vector
     */
    public void rebuildPartition(int partitionIndex, Map<String, Map<String, Float>> sparseVecs) {
        ensurePartition(partitionIndex);

        SpladeIndex newIndex = new SpladeIndex();
        for (Map.Entry<String, Map<String, Float>> entry : sparseVecs.entrySet()) {
            newIndex.indexSparse(entry.getKey(), entry.getValue());
        }

        partitions.set(partitionIndex, newIndex);
        log.debug("Rebuilt SPLADE for partition {} with {} documents", partitionIndex, sparseVecs.size());
    }

    /**
     * Adds a new empty partition (called when a partition rolls).
     *
     * @return the index of the newly added partition
     */
    public int addPartition() {
        SpladeIndex newIndex = new SpladeIndex();
        partitions.add(newIndex);
        int idx = partitions.size() - 1;
        log.debug("Added SPLADE partition {}", idx);
        return idx;
    }

    /** Returns the number of partitions. */
    public int partitionCount() {
        return partitions.size();
    }

    /** Returns the total number of indexed documents across all partitions. */
    public int totalDocuments() {
        int total = 0;
        for (SpladeIndex idx : partitions) {
            total += idx.size();
        }
        return total;
    }

    /** Returns the SPLADE index for a specific partition. */
    public SpladeIndex partition(int partitionIndex) {
        return partitions.get(partitionIndex);
    }

    @Override
    public void close() {
        for (SpladeIndex idx : partitions) {
            idx.close();
        }
        partitions.clear();
    }

    // ── Internal helpers ──

    private void ensurePartition(int partitionIndex) {
        while (partitions.size() <= partitionIndex) {
            partitions.add(new SpladeIndex());
        }
    }

    private List<SpladeCandidate> searchSequential(List<SpladeIndex> snapshot,
                                                    Map<String, Float> querySparse, int topK) {
        List<SpladeCandidate> results = new ArrayList<>();
        for (int i = 0; i < snapshot.size(); i++) {
            SpladeIndex idx = snapshot.get(i);
            if (idx.size() > 0) {
                ScoredResult[] sr = idx.searchSparse(querySparse, topK);
                for (ScoredResult r : sr) {
                    results.add(new SpladeCandidate(r.id(), r.score(), i));
                }
            }
        }
        return results;
    }
}
