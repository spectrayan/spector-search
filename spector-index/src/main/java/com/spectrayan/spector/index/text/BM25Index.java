package com.spectrayan.spector.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BM25-scored inverted index for keyword search.
 *
 * <p>Implements the Okapi BM25 ranking function over an inverted index.
 * Documents are analyzed via a pluggable {@link Analyzer} and stored as
 * posting lists mapping terms to document IDs and term frequencies.</p>
 *
 * <h3>BM25 Formula</h3>
 * <pre>
 *   score(D, Q) = Σ IDF(qi) · (f(qi, D) · (k1 + 1)) / (f(qi, D) + k1 · (1 - b + b · |D|/avgdl))
 *
 *   IDF(qi) = ln((N - n(qi) + 0.5) / (n(qi) + 0.5) + 1)
 * </pre>
 *
 * <h3>Performance Optimizations</h3>
 * <ul>
 *   <li><b>float[] score array</b> — eliminates HashMap boxing overhead for O(1) accumulation</li>
 *   <li><b>Bounded min-heap top-K</b> — O(N log K) via NeighborQueue instead of O(N log N) full sort</li>
 *   <li><b>int[] docLengths</b> — primitive array for cache-friendly access during scoring</li>
 *   <li><b>Parallel term scoring</b> — multi-term queries scored in parallel via virtual threads</li>
 *   <li><b>ReadWriteLock</b> — concurrent reads during search, exclusive writes during indexing</li>
 * </ul>
 *
 * <p>Default parameters: k1 = 1.2, b = 0.75</p>
 */
public class BM25Index implements KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(BM25Index.class);

    /** Threshold: use parallel term scoring when total postings exceed this.
     * Set conservatively — virtual thread scheduling overhead only pays off
     * for large posting lists. Below 20K, sequential scoring is faster. */
    private static final int PARALLEL_POSTING_THRESHOLD = 20_000;

    private final Analyzer analyzer;
    private final float k1;
    private final float b;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    // ── Inverted index ──
    private final Map<String, List<Posting>> invertedIndex;  // term → postings

    // ── Document metadata ──
    private final List<String> docIds;               // index → doc ID
    private final Map<String, Integer> docIdToIndex;  // doc ID → index
    private int[] docLengthsArray;                   // index → doc length (primitive array)
    private int docLengthsCapacity;
    private long totalDocLength;  // running total for O(1) avg computation
    private double avgDocLength;
    private int totalDocs;

    /** A posting: document index + term frequency in that document. */
    private record Posting(int docIndex, int termFrequency) {}

    /**
     * Creates a BM25 index with a custom analyzer and parameters.
     *
     * @param analyzer the text analyzer
     * @param k1       term frequency saturation parameter (default 1.2)
     * @param b        document length normalization parameter (default 0.75)
     */
    public BM25Index(Analyzer analyzer, float k1, float b) {
        this.analyzer = analyzer;
        this.k1 = k1;
        this.b = b;
        this.invertedIndex = new HashMap<>();
        this.docIds = new ArrayList<>();
        this.docIdToIndex = new HashMap<>();
        this.docLengthsCapacity = 1024;
        this.docLengthsArray = new int[docLengthsCapacity];
        this.totalDocLength = 0;
        this.avgDocLength = 0;
        this.totalDocs = 0;
    }

    /** Creates a BM25 index with default parameters (k1=1.2, b=0.75). */
    public BM25Index(Analyzer analyzer) {
        this(analyzer, 1.2f, 0.75f);
    }

    /** Creates a BM25 index with the standard analyzer and default params. */
    public BM25Index() {
        this(new StandardAnalyzer());
    }

    @Override
    public void index(String id, String content) {
        rwLock.writeLock().lock();
        try {
            indexInternal(id, content);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void indexInternal(String id, String content) {
        // Remove old entry if re-indexing
        if (docIdToIndex.containsKey(id)) {
            removeDoc(id);
        }

        List<String> terms = analyzer.analyze(content);
        int docIndex = docIds.size();

        docIds.add(id);
        docIdToIndex.put(id, docIndex);

        // Grow primitive doc lengths array if needed
        if (docIndex >= docLengthsCapacity) {
            docLengthsCapacity = Math.max(docLengthsCapacity * 2, docIndex + 1);
            docLengthsArray = Arrays.copyOf(docLengthsArray, docLengthsCapacity);
        }
        docLengthsArray[docIndex] = terms.size();

        totalDocs++;
        totalDocLength += terms.size();

        // Count term frequencies
        Map<String, Integer> termFreqs = new HashMap<>();
        for (String term : terms) {
            termFreqs.merge(term, 1, Integer::sum);
        }

        // Add to inverted index
        for (var entry : termFreqs.entrySet()) {
            invertedIndex
                    .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(new Posting(docIndex, entry.getValue()));
        }

        // Update average doc length — O(1) incremental
        avgDocLength = totalDocs > 0 ? (double) totalDocLength / totalDocs : 0;
    }

    @Override
    public ScoredResult[] search(String query, int k) {
        rwLock.readLock().lock();
        try {
            return searchInternal(query, k);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private ScoredResult[] searchInternal(String query, int k) {
        List<String> queryTerms = analyzer.analyze(query);
        if (queryTerms.isEmpty() || totalDocs == 0) {
            return new ScoredResult[0];
        }

        // ── Snapshot immutable state for thread-safe parallel scoring ──
        final int n = docIds.size();
        final int nDocs = totalDocs;
        final double avgDL = avgDocLength;
        final int[] docLens = docLengthsArray; // safe: only grows, never shrinks

        // ── Estimate total postings to decide parallel vs sequential ──
        int totalPostings = 0;
        List<String> validTerms = new ArrayList<>(queryTerms.size());
        for (String term : queryTerms) {
            List<Posting> postings = invertedIndex.get(term);
            if (postings != null) {
                totalPostings += postings.size();
                validTerms.add(term);
            }
        }
        if (validTerms.isEmpty()) {
            return new ScoredResult[0];
        }

        // ── Score using float[] array (zero-copy, no boxing) ──
        float[] scores;

        if (validTerms.size() > 1 && totalPostings >= PARALLEL_POSTING_THRESHOLD) {
            scores = scoreTermsParallel(validTerms, n, nDocs, avgDL, docLens);
        } else {
            scores = scoreTermsSequential(validTerms, n, nDocs, avgDL, docLens);
        }

        // ── Extract top-K using bounded min-heap: O(N log K) ──
        var heap = new NeighborQueue(Math.min(k, 64), k, true); // min-heap: smallest on top
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0f) {
                heap.add(i, scores[i]);
            }
        }

        // ── Build result array directly ──
        int resultCount = heap.size();
        ScoredResult[] results = new ScoredResult[resultCount];
        // Poll from min-heap gives ascending order; fill array back-to-front for descending
        for (int i = resultCount - 1; i >= 0; i--) {
            float score = heap.topScore();
            int idx = heap.poll();
            results[i] = new ScoredResult(docIds.get(idx), idx, score);
        }

        return results;
    }

    /**
     * Scores all terms sequentially into a single float[] array.
     */
    private float[] scoreTermsSequential(List<String> terms, int n,
                                          int nDocs, double avgDL, int[] docLens) {
        float[] scores = new float[n];

        for (String term : terms) {
            List<Posting> postings = invertedIndex.get(term);
            if (postings == null) continue;
            float idf = computeIdf(postings.size(), nDocs);
            accumulatePostings(postings, idf, scores, docLens, avgDL);
        }

        return scores;
    }

    /**
     * Scores each term in parallel using virtual threads, then merges.
     *
     * <p>Each term's postings are scored into a separate float[] array on its own
     * virtual thread. The arrays are then merged with SIMD-friendly sequential addition.
     * This avoids contention on a shared scores array.</p>
     */
    private float[] scoreTermsParallel(List<String> terms, int n,
                                        int nDocs, double avgDL, int[] docLens) {
        float[] mergedScores = new float[n];

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<float[]>> futures = new ArrayList<>(terms.size());

            for (String term : terms) {
                futures.add(executor.submit(() -> {
                    List<Posting> postings = invertedIndex.get(term);
                    if (postings == null) return null;
                    float idf = computeIdf(postings.size(), nDocs);
                    float[] termScores = new float[n];
                    accumulatePostings(postings, idf, termScores, docLens, avgDL);
                    return termScores;
                }));
            }

            // Merge: add each per-term array into the merged result
            for (var future : futures) {
                float[] termScores = future.get();
                if (termScores != null) {
                    for (int i = 0; i < n; i++) {
                        mergedScores[i] += termScores[i];
                    }
                }
            }
        } catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            log.warn("Parallel BM25 scoring interrupted", e);
        } catch (ExecutionException e) {
            log.error("Parallel BM25 scoring failed, falling back to sequential", e.getCause());
            return scoreTermsSequential(terms, n, nDocs, avgDL, docLens);
        }

        return mergedScores;
    }

    /**
     * Inner scoring loop — accumulates BM25 term scores into the scores array.
     * Kept as a tight loop for maximum throughput.
     */
    private void accumulatePostings(List<Posting> postings, float idf,
                                     float[] scores, int[] docLens, double avgDL) {
        final float avgDLf = (float) avgDL;
        final float k1PlusOne = k1 + 1f;
        final float oneMinusB = 1f - b;

        for (int i = 0, sz = postings.size(); i < sz; i++) {
            Posting p = postings.get(i);
            int docIndex = p.docIndex();
            int tf = p.termFrequency();
            int docLen = docLens[docIndex];

            float tfNorm = (tf * k1PlusOne)
                    / (tf + k1 * (oneMinusB + b * docLen / avgDLf));

            scores[docIndex] += idf * tfNorm;
        }
    }

    @Override
    public int size() {
        return totalDocs;
    }

    @Override
    public void remove(String id) {
        rwLock.writeLock().lock();
        try {
            removeDoc(id);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        rwLock.writeLock().lock();
        try {
            invertedIndex.clear();
            docIds.clear();
            docIdToIndex.clear();
            docLengthsArray = new int[1024];
            docLengthsCapacity = 1024;
            totalDocLength = 0;
            totalDocs = 0;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the analyzer used by this index.
     *
     * @return the analyzer
     */
    public Analyzer analyzer() {
        return analyzer;
    }

    // ─────────────── BM25 internals ───────────────

    /**
     * Computes the IDF (Inverse Document Frequency) component.
     *
     * <p>Uses the BM25 IDF variant: ln((N - n + 0.5) / (n + 0.5) + 1)</p>
     *
     * @param docFreq number of documents containing the term
     * @param numDocs total number of documents
     * @return IDF score
     */
    private float computeIdf(int docFreq, int numDocs) {
        return (float) Math.log(
                ((double) numDocs - docFreq + 0.5) / (docFreq + 0.5) + 1.0
        );
    }

    private void recalcAvgDocLength() {
        long total = 0;
        int n = docIds.size();
        for (int i = 0; i < n; i++) {
            total += docLengthsArray[i];
        }
        totalDocLength = total;
        avgDocLength = totalDocs > 0 ? (double) totalDocLength / totalDocs : 0;
    }

    private void removeDoc(String id) {
        // Simple removal: mark as removed but don't compact
        // For a production system, we'd implement proper deletion
        Integer idx = docIdToIndex.remove(id);
        if (idx != null) {
            totalDocs--;
            totalDocLength -= docLengthsArray[idx];
            // Remove postings (expensive but correct for re-index)
            for (var postings : invertedIndex.values()) {
                postings.removeIf(p -> p.docIndex() == idx);
            }
        }
    }
}
