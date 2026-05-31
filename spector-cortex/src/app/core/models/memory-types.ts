// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Memory Tier & Cognitive Profile Enums
// ═══════════════════════════════════════════════════════════════════════

/** Memory tier classification — maps to Java MemoryType enum. */
export enum MemoryTier {
  WORKING = 'WORKING',
  EPISODIC = 'EPISODIC',
  SEMANTIC = 'SEMANTIC',
  PROCEDURAL = 'PROCEDURAL',
}

/** Cognitive graph layer for visualization. */
export enum GraphLayer {
  HEBBIAN = 'HEBBIAN',
  TEMPORAL = 'TEMPORAL',
  ENTITY = 'ENTITY',
}

/**
 * Cognitive profile — maps to Java CognitiveProfile enum.
 * Each profile has unique α/β weights and visual behavior.
 */
export enum CognitiveProfile {
  BALANCED = 'BALANCED',
  EXPLORING = 'EXPLORING',
  DEBUGGING = 'DEBUGGING',
  RECALLING = 'RECALLING',
  CRITICAL = 'CRITICAL',
  HYPERFOCUS = 'HYPERFOCUS',
  SYSTEMATIZER = 'SYSTEMATIZER',
  DIVERGENT = 'DIVERGENT',
  PARANOID_SENTINEL = 'PARANOID_SENTINEL',
  THE_EXECUTOR = 'THE_EXECUTOR',
  HIGHLY_SENSITIVE = 'HIGHLY_SENSITIVE',
  DEFAULT_MODE_NETWORK = 'DEFAULT_MODE_NETWORK',
}

/** Profile parameter set for radar chart display. */
export interface ProfileParams {
  readonly alpha: number;
  readonly beta: number;
  readonly strictness: number;
  readonly valenceMin: number;
  readonly valenceMax: number;
  readonly lateralMode: boolean;
  readonly hyperfocusBoost: number;
  readonly label: string;
  readonly description: string;
}

/** Mapping of all cognitive profiles to their parameters. */
export const PROFILE_PARAMS: Record<CognitiveProfile, ProfileParams> = {
  [CognitiveProfile.BALANCED]: {
    alpha: 0.6, beta: 0.4, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Balanced', description: 'Equal weight to similarity and importance',
  },
  [CognitiveProfile.EXPLORING]: {
    alpha: 0.8, beta: 0.2, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Exploring', description: 'Similarity-dominated for creative recall',
  },
  [CognitiveProfile.DEBUGGING]: {
    alpha: 0.3, beta: 0.7, strictness: 1.0,
    valenceMin: -128, valenceMax: -10,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Debugging', description: 'Importance-dominated, negative valence bias',
  },
  [CognitiveProfile.RECALLING]: {
    alpha: 0.4, beta: 0.6, strictness: 1.0,
    valenceMin: 10, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Recalling', description: 'Importance-dominated, positive valence bias',
  },
  [CognitiveProfile.CRITICAL]: {
    alpha: 0.2, beta: 0.8, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Critical', description: 'Heavily importance-dominated, full range',
  },
  [CognitiveProfile.HYPERFOCUS]: {
    alpha: 1.0, beta: 0.0, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 1.5,
    label: 'Hyperfocus', description: 'Pure similarity, zero time decay',
  },
  [CognitiveProfile.SYSTEMATIZER]: {
    alpha: 0.3, beta: 0.7, strictness: 10.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Systematizer', description: 'Lossless consolidation, cliff function',
  },
  [CognitiveProfile.DIVERGENT]: {
    alpha: 0.8, beta: 0.2, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: true, hyperfocusBoost: 0,
    label: 'Divergent', description: 'Lateral/orthogonal retrieval enabled',
  },
  [CognitiveProfile.PARANOID_SENTINEL]: {
    alpha: 0.2, beta: 0.8, strictness: 1.0,
    valenceMin: -128, valenceMax: -1,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Paranoid Sentinel', description: 'Threat detection, negative-only',
  },
  [CognitiveProfile.THE_EXECUTOR]: {
    alpha: 0.3, beta: 0.7, strictness: 10.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'The Executor', description: 'Strict matching, no lateral exploration',
  },
  [CognitiveProfile.HIGHLY_SENSITIVE]: {
    alpha: 0.7, beta: 0.3, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Highly Sensitive', description: 'Enhanced sensory processing depth',
  },
  [CognitiveProfile.DEFAULT_MODE_NETWORK]: {
    alpha: 0.2, beta: 0.8, strictness: 1.0,
    valenceMin: -128, valenceMax: 127,
    lateralMode: false, hyperfocusBoost: 0,
    label: 'Default Mode Network', description: 'Mind-wandering, deep consolidated knowledge',
  },
};

/** Connection status for SSE stream. */
export type ConnectionStatus = 'connected' | 'disconnected' | 'reconnecting';

/** Pipeline phase for funnel visualization. */
export interface PipelinePhase {
  readonly name: string;
  readonly count: number;
  readonly filtered: number;
  readonly filterPercentage: number;
  readonly color: string;
}
