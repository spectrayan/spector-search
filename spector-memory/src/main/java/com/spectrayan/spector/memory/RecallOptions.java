package com.spectrayan.spector.memory;

import com.spectrayan.spector.memory.synapse.SynapticTagEncoder;

/**
 * Builder for recall query configuration.
 *
 * <p>Controls how {@link SpectorMemory#recall} filters, scores, and returns
 * cognitive memories. Supports synaptic tag filtering, importance thresholds,
 * memory type selection, valence range filtering, and neurodivergent
 * cognitive profile mechanics (hyperfocus, lateral retrieval).</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 *   List<CognitiveResult> results = memory.recall("database lock timeout",
 *       RecallOptions.builder()
 *           .topK(5)
 *           .synapticFilter("debugging", "database")
 *           .minImportance(0.3f)
 *           .memoryTypes(MemoryType.SEMANTIC, MemoryType.EPISODIC)
 *           .maxValence((byte) -10)  // only negative-outcome memories
 *           .build());
 * }</pre>
 *
 * <h3>Neurodivergent Profiles</h3>
 * <pre>{@code
 *   // Hyperfocus: zero-decay, strict tag gate, pure similarity scoring
 *   RecallOptions opts = RecallOptions.builder()
 *       .profile(CognitiveProfile.HYPERFOCUS)
 *       .hyperfocusMask("database", "deadlock")
 *       .build();
 *
 *   // Lateral retrieval: cross-domain divergent thinking
 *   RecallOptions opts = RecallOptions.builder()
 *       .profile(CognitiveProfile.DIVERGENT)
 *       .lateralMode(true)
 *       .build();
 * }</pre>
 */
public record RecallOptions(
        int topK,
        long synapticTagMask,
        float minImportance,
        MemoryType[] memoryTypes,
        byte minValence,
        byte maxValence,
        float alpha,
        float beta,
        float tagRelevanceBoost,
        int semanticCandidateMultiplier,
        // ── Neurodivergent: Hyperfocus ──
        long hyperfocusMask,
        float hyperfocusBoost,
        // ── Neurodivergent: Lateral Retrieval ──
        boolean lateralMode,
        float lateralDistanceThreshold,
        int lateralMaxResults,
        float lateralMinTagOverlap,
        // ── Enhanced Scoring ──
        float strictnessCoefficient,
        // ── Valence Alignment (State-Dependent Recall) ──
        byte queryValence,
        boolean enableValenceAlignment
) {

    /** Default options: top 10, no filters, balanced scoring. */
    public static final RecallOptions DEFAULT = builder().build();

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link RecallOptions}.
     */
    public static final class Builder {

        private int topK = 10;
        private long synapticTagMask = 0L;
        private float minImportance = 0.0f;
        private MemoryType[] memoryTypes = null; // null = all types
        private byte minValence = Byte.MIN_VALUE;
        private byte maxValence = Byte.MAX_VALUE;
        private float alpha = 0.6f;  // similarity weight
        private float beta = 0.4f;   // importance × decay weight
        private float tagRelevanceBoost = 0.3f;  // weighted tag overlap boost
        private int semanticCandidateMultiplier = 3; // HNSW over-fetch for semantic

        // ── Neurodivergent: Hyperfocus ──
        private long hyperfocusMask = 0L;       // 0 = disabled
        private float hyperfocusBoost = 1.0f;   // post-score multiplier

        // ── Neurodivergent: Lateral Retrieval ──
        private boolean lateralMode = false;
        private float lateralDistanceThreshold = 1.2f;
        private int lateralMaxResults = -1;      // -1 = topK/3
        private float lateralMinTagOverlap = 0.5f;

        // ── Enhanced Scoring ──
        private float strictnessCoefficient = 1.0f; // 1.0 = standard, 10.0 = Heaviside cliff

        // ── Valence Alignment (State-Dependent Recall) ──
        private byte queryValence = 0;              // 0 = neutral
        private boolean enableValenceAlignment = false;

        /**
         * Applies a {@link CognitiveProfile} preset to this builder.
         *
         * <p>Sets alpha, beta, minValence, and maxValence from the profile.
         * Individual fields can be overridden after applying the profile.</p>
         *
         * @param profile the cognitive scoring profile to apply
         */
        public Builder profile(CognitiveProfile profile) {
            return profile.applyTo(this);
        }

        /**
         * Maximum number of results to return.
         */
        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Synaptic tag filter using Bloom filter matching.
         * Only memories whose tags match ALL specified tags will be considered.
         */
        public Builder synapticFilter(String... tags) {
            this.synapticTagMask = SynapticTagEncoder.encode(tags);
            return this;
        }

        /**
         * Minimum importance threshold — memories below this are skipped.
         */
        public Builder minImportance(float minImportance) {
            this.minImportance = minImportance;
            return this;
        }

        /**
         * Restrict recall to specific memory types.
         * Pass null or omit to search all types.
         */
        public Builder memoryTypes(MemoryType... memoryTypes) {
            this.memoryTypes = memoryTypes;
            return this;
        }

        /**
         * Minimum valence (inclusive). Use for filtering to positive outcomes.
         */
        public Builder minValence(byte minValence) {
            this.minValence = minValence;
            return this;
        }

        /**
         * Maximum valence (inclusive). Use for filtering to negative outcomes (debugging).
         */
        public Builder maxValence(byte maxValence) {
            this.maxValence = maxValence;
            return this;
        }

        /**
         * Scoring weight for vector similarity (default: 0.6).
         */
        public Builder alpha(float alpha) {
            this.alpha = alpha;
            return this;
        }

        /**
         * Scoring weight for importance × decay (default: 0.4).
         */
        public Builder beta(float beta) {
            this.beta = beta;
            return this;
        }

        /**
         * Boost factor for weighted tag relevance (default: 0.3).
         * Partial tag matches are scored as: score *= (1.0 + overlapRatio * tagRelevanceBoost).
         * Set to 0.0 to disable tag relevance boosting.
         */
        public Builder tagRelevanceBoost(float tagRelevanceBoost) {
            this.tagRelevanceBoost = tagRelevanceBoost;
            return this;
        }

        /**
         * Over-fetch multiplier for semantic HNSW search (default: 3).
         * Fetches topK * multiplier candidates from HNSW before cognitive re-ranking.
         */
        public Builder semanticCandidateMultiplier(int multiplier) {
            this.semanticCandidateMultiplier = multiplier;
            return this;
        }

        // ── Neurodivergent: Hyperfocus ──

        /**
         * Sets the hyperfocus Bloom filter mask from raw long value.
         * Memories that don't match ALL bits in this mask are excluded (strict equality gate).
         * Set to 0L to disable hyperfocus (default).
         */
        public Builder hyperfocusMask(long mask) {
            this.hyperfocusMask = mask;
            return this;
        }

        /**
         * Sets the hyperfocus mask from synaptic tag strings.
         * Encodes tags into a Bloom filter mask for strict equality gating.
         */
        public Builder hyperfocusMask(String... tags) {
            this.hyperfocusMask = SynapticTagEncoder.encode(tags);
            return this;
        }

        /**
         * Post-score multiplier for hyperfocus-matched memories (default: 1.0).
         * Applied after the normalized base score is computed.
         */
        public Builder hyperfocusBoost(float boost) {
            this.hyperfocusBoost = boost;
            return this;
        }

        // ── Neurodivergent: Lateral Retrieval ──

        /**
         * Enables lateral/orthogonal retrieval — finds tag-matched but semantically
         * distant memories for cross-domain insight (default: false).
         */
        public Builder lateralMode(boolean enabled) {
            this.lateralMode = enabled;
            return this;
        }

        /**
         * Minimum L2 distance for a memory to qualify as a lateral candidate (default: 1.2).
         * Higher values → only very distant memories are considered lateral.
         */
        public Builder lateralDistanceThreshold(float threshold) {
            this.lateralDistanceThreshold = threshold;
            return this;
        }

        /**
         * Maximum number of lateral candidates in the final results (default: topK/3).
         * Set to -1 for auto (topK/3).
         */
        public Builder lateralMaxResults(int max) {
            this.lateralMaxResults = max;
            return this;
        }

        /**
         * Minimum tag overlap ratio for lateral candidates (default: 0.5).
         * Prevents Bloom filter false positives from producing spurious lateral results.
         */
        public Builder lateralMinTagOverlap(float minOverlap) {
            this.lateralMinTagOverlap = minOverlap;
            return this;
        }

        // ── Enhanced Scoring ──

        /**
         * Strictness coefficient for the similarity function (default: 1.0).
         * Higher values create a steeper "cliff" — near-matches score well,
         * slightly vague matches plummet. Use 10.0 for SYSTEMATIZER / THE_EXECUTOR.
         */
        public Builder strictnessCoefficient(float k) {
            this.strictnessCoefficient = k;
            return this;
        }

        // ── Valence Alignment (State-Dependent Recall) ──

        /**
         * Sets the query's emotional valence for state-dependent recall.
         * Memories with similar valence score higher. Enables valence alignment automatically.
         */
        public Builder queryValence(byte valence) {
            this.queryValence = valence;
            this.enableValenceAlignment = true;
            return this;
        }

        /**
         * Explicitly enables/disables valence alignment scoring.
         */
        public Builder enableValenceAlignment(boolean enabled) {
            this.enableValenceAlignment = enabled;
            return this;
        }

        public RecallOptions build() {
            int effectiveLateralMax = lateralMaxResults >= 0
                    ? lateralMaxResults
                    : Math.max(1, topK / 3);
            return new RecallOptions(topK, synapticTagMask, minImportance,
                    memoryTypes, minValence, maxValence, alpha, beta,
                    tagRelevanceBoost, semanticCandidateMultiplier,
                    hyperfocusMask, hyperfocusBoost,
                    lateralMode, lateralDistanceThreshold,
                    effectiveLateralMax, lateralMinTagOverlap,
                    strictnessCoefficient, queryValence, enableValenceAlignment);
        }
    }
}
