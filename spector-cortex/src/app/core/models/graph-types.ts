// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Graph Type Interfaces
// ═══════════════════════════════════════════════════════════════════════
// Data models for the 3D neural graph visualization.

import { MemoryTier } from './memory-types';

/** A memory node in the 3D neural graph. */
export interface NeuralNode {
  readonly id: string;
  readonly index: number;
  readonly tier: MemoryTier;
  readonly importance: number;       // 0.0 – 1.0
  readonly valence: number;          // -128 to 127
  readonly arousal: number;          // 0 – 255
  readonly recallCount: number;
  readonly decayFactor: number;      // 0.0 – 1.0
  readonly isResolved: boolean;      // Zeigarnik flag
  readonly isPinned: boolean;
  readonly isTombstoned: boolean;
  readonly synapticTags: number;     // 64-bit bitmask
  readonly label: string;

  // Visual state (mutable during animation)
  activation: number;               // 0.0 = dormant, 1.0 = fully firing
  position: [number, number, number];
}

/** Hebbian edge — undirected association between memory nodes. */
export interface HebbianEdge {
  readonly from: number;             // source node index
  readonly to: number;               // target node index
  readonly weight: number;           // association strength 0.0 – 1.0
  activation: number;               // glow intensity during spreading activation
}

/** Temporal link — directed session-ordered connection. */
export interface TemporalLink {
  readonly from: number;
  readonly to: number;
  readonly sessionId: number;
  activation: number;
}

/** Entity relation — typed knowledge graph edge. */
export interface EntityRelation {
  readonly sourceEntity: string;
  readonly targetEntity: string;
  readonly relationType: string;
  readonly weight: number;
  activation: number;
}

/** Entity node in the knowledge graph. */
export interface EntityNode {
  readonly name: string;
  readonly entityType: string;
  readonly memoryRefCount: number;
  activation: number;
  position: [number, number, number];
}

/** Active traversal path for query animation. */
export interface TraversalPath {
  readonly queryText: string;
  readonly visitedNodes: number[];
  readonly activeEdges: Array<[number, number]>;
  readonly phase: 'tag-gate' | 'vector-scan' | 'hebbian' | 'temporal' | 'entity' | 'complete';
  readonly progress: number;         // 0.0 – 1.0
}

/** Memory segment for heatmap visualization. */
export interface MemorySegmentInfo {
  readonly name: string;
  readonly tier: MemoryTier | 'HEBBIAN' | 'TEMPORAL' | 'ENTITY' | 'COACTIVATION' | 'STDP';
  readonly sizeBytes: number;
  readonly usedBytes: number;
  readonly recordCount: number;
  readonly bytesPerRecord: number;
  readonly heatIntensity: number;    // 0.0 – 1.0 (read/write activity)
}

/** SIMD register lane state. */
export interface SimdLaneState {
  readonly laneIndex: number;
  readonly isActive: boolean;
  readonly value: number;
  intensity: number;                 // glow intensity 0.0 – 1.0
}
