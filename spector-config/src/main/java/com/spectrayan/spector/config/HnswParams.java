package com.spectrayan.spector.config;

import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Configuration parameters for the HNSW (Hierarchical Navigable Small World) index.
 *
 * @param m               max bi-directional connections per node per layer (default 16)
 * @param efConstruction   beam width during index construction (default 200)
 * @param efSearch         beam width during search (default 50)
 * @param maxLevel0Connections max connections at layer 0 (typically 2 × m)
 * @param levelMultiplier  controls the probability of a node appearing at higher layers (1/ln(m))
 */
public record HnswParams(
        int m,
        int efConstruction,
        int efSearch,
        int maxLevel0Connections,
        double levelMultiplier
) {
    /** Sensible defaults for most use cases. */
    public static final HnswParams DEFAULT = new HnswParams(16, 200, 50);

    /**
     * Creates params with computed level-0 connections and level multiplier.
     */
    public HnswParams(int m, int efConstruction, int efSearch) {
        this(m, efConstruction, efSearch, 2 * m, 1.0 / Math.log(m));
    }

    public HnswParams {
        if (m < 2) throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID, "m", m + " (must be >= 2)");
        if (efConstruction < 1) throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID, "efConstruction", efConstruction + " (must be >= 1)");
        if (efSearch < 1) throw new SpectorConfigException(ErrorCode.CONFIG_VALUE_INVALID, "efSearch", efSearch + " (must be >= 1)");
    }

    /**
     * Returns a copy with a different efSearch value.
     */
    public HnswParams withEfSearch(int newEfSearch) {
        return new HnswParams(m, efConstruction, newEfSearch, maxLevel0Connections, levelMultiplier);
    }
}
