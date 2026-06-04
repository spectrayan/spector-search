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
import com.spectrayan.spector.index.BM25Index;
import com.spectrayan.spector.index.ScoredResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-partition BM25 index manager for text similarity search.
 *
 * <h3>Architecture</h3>
 * <p>Maintains an array of {@link BM25Index} instances, one per memory partition.
 * On recall, searches all partitions in parallel using virtual threads and merges
 * results by BM25 score (descending). On startup, each partition's BM25 is rebuilt
 * from its {@code text.dat} file — zero persistent BM25 storage.</p>
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li><b>Startup</b>: {@link #rebuildPartition(int, Map)} called per partition with texts from text.dat</li>
 *   <li><b>Ingest</b>: {@link #index(int, String, String)} called per memory ingestion</li>
 *   <li><b>Recall</b>: {@link #search(String, int)} fans out across all partitions</li>
 *   <li><b>Roll</b>: {@link #addPartition()} creates a new empty BM25 for the new active partition</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>The partition list uses {@link CopyOnWriteArrayList} for safe concurrent reads
 * during search while allowing partition additions. Individual {@link BM25Index}
 * instances use internal {@code ReadWriteLock} for concurrent read/write safety.</p>
 *
 * @see BM25Index
 * @see TextDataStore
 */
public final class MemoryBM25Index implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryBM25Index.class);

    /**
     * A BM25 search result with its source partition.
     *
     * @param id             memory identifier
     * @param bm25Score      the BM25 relevance score
     * @param partitionIndex which partition this result came from
     */
    public record BM25Candidate(String id, float bm25Score, int partitionIndex) {}

    private final CopyOnWriteArrayList<BM25Index> partitions;

    /**
     * Creates an empty MemoryBM25Index with no partitions.
     * Call {@link #addPartition()} or {@link #rebuildPartition(int, Map)} to populate.
     */
    public MemoryBM25Index() {
        this.partitions = new CopyOnWriteArrayList<>();
    }

    /**
     * Creates a MemoryBM25Index with the given number of pre-allocated empty partitions.
     *
     * @param partitionCount number of partitions to pre-allocate
     */
    public MemoryBM25Index(int partitionCount) {
        this.partitions = new CopyOnWriteArrayList<>();
        for (int i = 0; i < partitionCount; i++) {
            partitions.add(new BM25Index());
        }
    }

    /**
     * Indexes a text entry into a specific partition's BM25 index.
     *
     * @param partitionIndex the target partition
     * @param id             memory identifier
     * @param text           the raw text content to index
     */
    public void index(int partitionIndex, String id, String text) {
        ensurePartition(partitionIndex);
        partitions.get(partitionIndex).index(id, text);
    }

    /**
     * Searches all partitions in parallel and returns merged results.
     *
     * <p>Uses {@link ConcurrentTasks#forkJoinAll} to fan out BM25 search
     * across all non-empty partitions. Results are merged and sorted by
     * BM25 score descending, limited to topK.</p>
     *
     * @param query the search query text
     * @param topK  maximum number of results to return
     * @return list of BM25 candidates sorted by score descending
     */
    public List<BM25Candidate> search(String query, int topK) {
        if (partitions.isEmpty()) {
            return List.of();
        }

        // Snapshot partition list for consistent iteration
        List<BM25Index> snapshot = new ArrayList<>(partitions);

        // Build parallel search tasks
        List<Callable<List<BM25Candidate>>> tasks = new ArrayList<>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            final int partIdx = i;
            final BM25Index idx = snapshot.get(i);
            if (idx.size() > 0) {
                tasks.add(() -> {
                    ScoredResult[] results = idx.search(query, topK);
                    List<BM25Candidate> candidates = new ArrayList<>(results.length);
                    for (ScoredResult sr : results) {
                        candidates.add(new BM25Candidate(sr.id(), sr.score(), partIdx));
                    }
                    return candidates;
                });
            }
        }

        if (tasks.isEmpty()) {
            return List.of();
        }

        // Execute in parallel if multiple partitions, sequential if single
        List<BM25Candidate> merged;
        if (tasks.size() == 1) {
            try {
                merged = tasks.getFirst().call();
            } catch (Exception e) {
                log.error("BM25 single-partition search failed", e);
                return List.of();
            }
        } else {
            merged = new ArrayList<>();
            try {
                List<List<BM25Candidate>> partitionResults = ConcurrentTasks.forkJoinAll(tasks);
                for (List<BM25Candidate> pr : partitionResults) {
                    merged.addAll(pr);
                }
            } catch (ConcurrentExecutionException e) {
                log.error("Parallel BM25 search failed, falling back to sequential", e);
                merged = searchSequential(snapshot, query, topK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("BM25 search interrupted");
                return List.of();
            }
        }

        // Sort by score descending and limit to topK
        merged.sort(Comparator.comparingDouble(BM25Candidate::bm25Score).reversed());
        if (merged.size() > topK) {
            return merged.subList(0, topK);
        }
        return merged;
    }

    /**
     * Rebuilds a single partition's BM25 index from its text data.
     *
     * <p>Called on startup when loading text.dat for each partition.
     * Clears any existing index for that partition and re-indexes all entries.</p>
     *
     * @param partitionIndex the partition to rebuild
     * @param texts          map of memory ID → text content
     */
    public void rebuildPartition(int partitionIndex, Map<String, String> texts) {
        ensurePartition(partitionIndex);

        // Close old index and create a fresh one
        BM25Index newIndex = new BM25Index();
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            newIndex.index(entry.getKey(), entry.getValue());
        }

        partitions.set(partitionIndex, newIndex);
        log.debug("Rebuilt BM25 for partition {} with {} documents", partitionIndex, texts.size());
    }

    /**
     * Adds a new empty partition (called when a partition rolls).
     *
     * @return the index of the newly added partition
     */
    public int addPartition() {
        BM25Index newIndex = new BM25Index();
        partitions.add(newIndex);
        int idx = partitions.size() - 1;
        log.debug("Added BM25 partition {}", idx);
        return idx;
    }

    /**
     * Returns the number of partitions.
     */
    public int partitionCount() {
        return partitions.size();
    }

    /**
     * Returns the total number of indexed documents across all partitions.
     */
    public int totalDocuments() {
        int total = 0;
        for (BM25Index idx : partitions) {
            total += idx.size();
        }
        return total;
    }

    /**
     * Returns the BM25 index for a specific partition (for direct access).
     *
     * @param partitionIndex the partition index
     * @return the BM25Index for that partition
     */
    public BM25Index partition(int partitionIndex) {
        return partitions.get(partitionIndex);
    }

    @Override
    public void close() {
        for (BM25Index idx : partitions) {
            idx.close();
        }
        partitions.clear();
    }

    // ── Internal helpers ──

    private void ensurePartition(int partitionIndex) {
        while (partitions.size() <= partitionIndex) {
            partitions.add(new BM25Index());
        }
    }

    private List<BM25Candidate> searchSequential(List<BM25Index> snapshot, String query, int topK) {
        List<BM25Candidate> results = new ArrayList<>();
        for (int i = 0; i < snapshot.size(); i++) {
            BM25Index idx = snapshot.get(i);
            if (idx.size() > 0) {
                ScoredResult[] sr = idx.search(query, topK);
                for (ScoredResult r : sr) {
                    results.add(new BM25Candidate(r.id(), r.score(), i));
                }
            }
        }
        return results;
    }
}
