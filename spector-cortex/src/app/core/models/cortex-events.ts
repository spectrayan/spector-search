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
 * SIMD lane event — emitted per-query with aggregate SIMD execution stats.
 * Reports which SIMD kernel was used, the lane width, vectors processed, and duration.
 */
export interface SimdLaneEvent extends CortexEvent {
  readonly eventType: 'cortex.simd.lane';
  readonly kernelName: string;
  readonly laneWidth: number;
  readonly vectorsProcessed: number;
  readonly durationMicros: number;
  readonly fallbackNanos: number;
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
 * Graph pulse event — emitted per-query with aggregate spreading
 * activation, temporal chain traversal, and entity BFS stats.
 */
export interface GraphPulseEvent extends CortexEvent {
  readonly eventType: 'cortex.graph.pulse';
  readonly nodesVisited: number;
  readonly edgesTraversed: number;
  readonly maxDepth: number;
  readonly durationMicros: number;
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

/**
 * Memory snapshot event — emitted before and after reflect() consolidation.
 * Used by the memory diff view to show before/after comparison.
 */
export interface MemorySnapshotEvent extends CortexEvent {
  readonly eventType: 'cortex.memory.snapshot';
  readonly phase: 'pre-reflect' | 'post-reflect';
  readonly reflectCycleId: string;
  readonly hebbianEdgeCount: number;
  readonly temporalLinkCount: number;
  readonly entityNodeCount: number;
  readonly entityEdgeCount: number;
  readonly offHeapBytes: number;
  readonly tombstoneCount: number;
  readonly coActivationPairs: number;
  readonly stdpEdges: number;
}

/**
 * GPU kernel event — emitted during CUDA kernel execution.
 * Used by the GPU timeline panel.
 */
export interface GpuKernelEvent extends CortexEvent {
  readonly eventType: 'cortex.gpu.kernel';
  readonly streamIndex: number;
  readonly kernelName: string;
  readonly durationMicros: number;
  readonly gridDim: [number, number, number];
  readonly blockDim: [number, number, number];
  readonly memoryTransferBytes: number;
}

/**
 * Cluster node info — state of a single node in the cluster.
 */
export interface ClusterNodeInfo {
  readonly nodeId: string;
  readonly status: 'active' | 'draining' | 'down';
  readonly shardCount: number;
  readonly memoryUsedBytes: number;
  readonly queryRate: number;
}

/**
 * Cluster topology event — emitted periodically with full cluster state.
 */
export interface ClusterTopologyEvent extends CortexEvent {
  readonly eventType: 'cortex.cluster.topology';
  readonly nodes: ClusterNodeInfo[];
  readonly replicationLinks: Array<[string, string]>;
}

/** Projected 3D point for vector space visualization. */
export interface ProjectedPointDto {
  readonly id: string;
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly tier: string;
  readonly importance: number;
  readonly label: string;
}

/**
 * Embedding projection event — carries PCA/random-projected 3D
 * coordinates of stored vectors for the vector space panel.
 */
export interface EmbeddingProjectionEvent extends CortexEvent {
  readonly eventType: 'cortex.embedding.projection';
  readonly points: ProjectedPointDto[];
  readonly queryProjection: [number, number, number] | null;
}

/** Union type for all cortex events */
export type AnyCortexEvent =
  | QueryTraceEvent
  | SimdLaneEvent
  | MemoryDiagnosticEvent
  | GraphPulseEvent
  | ReflectCycleEvent
  | MemorySnapshotEvent
  | GpuKernelEvent
  | ClusterTopologyEvent
  | EmbeddingProjectionEvent
  | IngestionProgressEvent
  | IngestionCompletedEvent;

/**
 * Ingestion progress event — emitted periodically during async ingestion.
 */
export interface IngestionProgressEvent extends CortexEvent {
  readonly eventType: 'ingestion.progress';
  readonly taskId: string;
  readonly description: string;
  readonly chunksStored: number;
  readonly totalChunks: number;
  readonly failures: number;
  readonly progressPercent: number;
}

/**
 * Ingestion completed event — emitted when an async ingestion task finishes.
 */
export interface IngestionCompletedEvent extends CortexEvent {
  readonly eventType: 'ingestion.completed';
  readonly taskId: string;
  readonly description: string;
  readonly chunksStored: number;
  readonly failures: number;
  readonly durationMs: number;
  readonly success: boolean;
}

