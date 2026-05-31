// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Event Type Interfaces
// ═══════════════════════════════════════════════════════════════════════
// SSE event payloads from the Spector node backend.
// Maps to the Java SpectorEvent hierarchy in spector-node.

/** Base interface for all cortex SSE events. */
export interface CortexEvent {
  readonly eventType: string;
  readonly timestamp: number;
  readonly nodeId: string;
}

/**
 * Query trace event — emitted after each recall pipeline execution.
 * Shows per-phase record survival counts for the scoring funnel.
 */
export interface QueryTraceEvent extends CortexEvent {
  readonly eventType: 'cortex.query.trace';
  readonly queryText: string;
  readonly cognitiveProfile: string;
  readonly synapticTagMask: number;
  readonly totalRecords: number;
  readonly afterTombstone: number;
  readonly afterTagGate: number;
  readonly afterValence: number;
  readonly afterDecay: number;
  readonly afterVectorDistance: number;
  readonly finalTopK: number;
  readonly hebbianActivated: number;
  readonly temporalLinked: number;
  readonly entityDiscovered: number;
  readonly latencyMicros: number;
}

/**
 * SIMD lane event — emitted during vector operations.
 * Reports lane utilization and kernel activity.
 */
export interface SimdLaneEvent extends CortexEvent {
  readonly eventType: 'cortex.simd.lane';
  readonly vectorBitSize: number;
  readonly laneCount: number;
  readonly totalIterations: number;
  readonly tailLanesActive: number;
  readonly activeKernel: string;
  readonly fmaOpsCount: number;
}

/**
 * Memory diagnostic event — periodic system health snapshot.
 * Emitted every ~1s when dashboard is connected.
 */
export interface MemoryDiagnosticEvent extends CortexEvent {
  readonly eventType: 'cortex.memory.diagnostic';
  readonly offHeapBytes: number;
  readonly pinnedBytes: number;
  readonly jvmHeapUsed: number;
  readonly jvmHeapMax: number;
  readonly gpuAllocated: number;
  readonly gpuFree: number;
  readonly softPageFaults: number;
  readonly hardPageFaults: number;
  readonly workingCount: number;
  readonly episodicCount: number;
  readonly semanticCount: number;
  readonly proceduralCount: number;
  readonly hebbianEdges: number;
  readonly temporalLinks: number;
  readonly entityNodes: number;
  readonly entityEdges: number;
  readonly coActivationPairs: number;
  readonly stdpEdges: number;
}

/**
 * Graph pulse event — emitted during spreading activation,
 * temporal chain traversal, or entity BFS.
 */
export interface GraphPulseEvent extends CortexEvent {
  readonly eventType: 'cortex.graph.pulse';
  readonly graphType: 'hebbian' | 'temporal' | 'entity';
  readonly sourceNode: number;
  readonly activatedEdges: Array<[number, number]>; // [targetNode, weight×1000]
  readonly depth: number;
}

/**
 * Reflect cycle event — emitted after memory consolidation.
 */
export interface ReflectCycleEvent extends CortexEvent {
  readonly eventType: 'cortex.reflect.cycle';
  readonly hebbianEdgesDecayed: number;
  readonly hebbianEdgesRemoved: number;
  readonly decayFactor: number;
  readonly durationMs: number;
}

/** Union type for all cortex events */
export type AnyCortexEvent =
  | QueryTraceEvent
  | SimdLaneEvent
  | MemoryDiagnosticEvent
  | GraphPulseEvent
  | ReflectCycleEvent;
