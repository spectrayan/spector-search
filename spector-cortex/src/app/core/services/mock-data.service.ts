// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Mock Data Service
// ═══════════════════════════════════════════════════════════════════════
// Generates realistic simulated events for standalone development.
// Mimics the actual Spector recall pipeline behavior.

import { Injectable, inject, OnDestroy } from '@angular/core';
import { CortexStateService, MetricsPoint, DecayPoint } from './cortex-state.service';
import {
  QueryTraceEvent,
  SimdLaneEvent,
  MemoryDiagnosticEvent,
  GraphPulseEvent,
  ReflectCycleEvent,
  MemorySnapshotEvent,
  GpuKernelEvent,
  ClusterTopologyEvent,
} from '../models/cortex-events';
import { CognitiveProfile } from '../models/memory-types';

const SAMPLE_QUERIES = [
  'database connection timeout error',
  'user authentication flow',
  'implement caching strategy for API responses',
  'fix memory leak in event handler',
  'refactor payment processing module',
  'design microservice architecture',
  'optimize SQL query performance',
  'resolve CORS issue in frontend',
  'add rate limiting to REST API',
  'deploy to Kubernetes cluster',
  'implement WebSocket real-time updates',
  'debug race condition in async handler',
];

const PROFILES = Object.values(CognitiveProfile);
const KERNELS = ['cosine', 'dotProduct', 'euclidean', 'svasq4', 'packedDot'];
const TIERS = ['WORKING', 'EPISODIC', 'SEMANTIC', 'PROCEDURAL'];
const LABELS = [
  'auth-flow', 'db-pool', 'cache-hit', 'user-session', 'api-rate-limit',
  'jwt-decode', 'cors-policy', 'event-loop', 'gc-pause', 'thread-pool',
  'ssl-cert', 'dns-resolve', 'retry-logic', 'circuit-breaker', 'load-balance',
  'schema-migrate', 'queue-drain', 'pub-sub', 'batch-job', 'cron-trigger',
  'webhook-retry', 'health-check', 'blue-green', 'canary-deploy', 'rollback',
  'mem-leak-fix', 'cpu-bound', 'io-wait', 'deadlock', 'starvation',
];

@Injectable({ providedIn: 'root' })
export class MockDataService implements OnDestroy {

  private readonly state = inject(CortexStateService);

  private queryInterval: ReturnType<typeof setInterval> | null = null;
  private simdInterval: ReturnType<typeof setInterval> | null = null;
  private diagInterval: ReturnType<typeof setInterval> | null = null;
  private graphInterval: ReturnType<typeof setInterval> | null = null;
  private reflectInterval: ReturnType<typeof setInterval> | null = null;
  private metricsInterval: ReturnType<typeof setInterval> | null = null;
  private habituationInterval: ReturnType<typeof setInterval> | null = null;
  private gpuInterval: ReturnType<typeof setInterval> | null = null;
  private clusterInterval: ReturnType<typeof setInterval> | null = null;
  private profileIndex = 0;
  private queryCounter = 0;
  private reflectCycleCounter = 0;

  /** Start emitting mock events at realistic intervals. */
  start(): void {
    this.state.connectionStatus.set('connected');

    // Generate initial vector space points
    this.generateVectorSpace();

    // Generate decay curve (static shape, varies per profile)
    this.generateDecayCurve();

    // Set initial Zeigarnik counts
    this.state.unresolvedCount.set(7);
    this.state.totalTaskCount.set(45);

    // Query traces every 2–4 seconds
    this.queryInterval = setInterval(() => {
      this.emitQueryTrace();
    }, 2000 + Math.random() * 2000);

    // SIMD events every 500ms
    this.simdInterval = setInterval(() => {
      this.emitSimdEvent();
    }, 500);

    // Memory diagnostics every 1s
    this.diagInterval = setInterval(() => {
      this.emitMemoryDiag();
    }, 1000);

    // Graph pulses every 1.5–3s
    this.graphInterval = setInterval(() => {
      this.emitGraphPulse();
    }, 1500 + Math.random() * 1500);

    // Reflect cycles every 10–15s
    this.reflectInterval = setInterval(() => {
      this.emitReflect();
    }, 10000 + Math.random() * 5000);

    // Metrics time-series every 1s
    this.metricsInterval = setInterval(() => {
      this.emitMetrics();
    }, 1000);

    // Habituation updates every 2s
    this.habituationInterval = setInterval(() => {
      this.updateHabituation();
    }, 2000);

    // GPU kernel events every 100-300ms
    this.gpuInterval = setInterval(() => {
      this.emitGpuKernel();
    }, 100 + Math.random() * 200);

    // Cluster topology every 5s
    this.clusterInterval = setInterval(() => {
      this.emitClusterTopology();
    }, 5000);

    // Emit initial data immediately
    this.emitMemoryDiag();
    this.emitSimdEvent();
    this.emitQueryTrace();
    this.emitMetrics();
    this.emitClusterTopology();
  }

  /** Stop all mock event emission. */
  stop(): void {
    [this.queryInterval, this.simdInterval, this.diagInterval,
     this.graphInterval, this.reflectInterval, this.metricsInterval,
     this.habituationInterval, this.gpuInterval, this.clusterInterval].forEach(id => {
      if (id !== null) clearInterval(id);
    });
    this.queryInterval = this.simdInterval = this.diagInterval =
      this.graphInterval = this.reflectInterval = this.metricsInterval =
      this.habituationInterval = this.gpuInterval = this.clusterInterval = null;
    this.state.connectionStatus.set('disconnected');
  }

  ngOnDestroy(): void {
    this.stop();
  }

  // ── Emitters ───────────────────────────────────────────────────────

  private emitQueryTrace(): void {
    this.queryCounter++;
    const total = 500_000 + Math.floor(Math.random() * 1_500_000);
    const afterTombstone = Math.floor(total * (0.990 + Math.random() * 0.008));
    const afterTagGate = Math.floor(afterTombstone * (0.005 + Math.random() * 0.025));
    const afterValence = Math.floor(afterTagGate * (0.85 + Math.random() * 0.12));
    const afterDecay = Math.floor(afterValence * (0.60 + Math.random() * 0.30));
    const afterVector = afterDecay;
    const finalTopK = Math.min(10 + Math.floor(Math.random() * 5), afterVector);

    const profile = PROFILES[this.profileIndex % PROFILES.length];
    this.profileIndex++;

    const event: QueryTraceEvent = {
      eventType: 'cortex.query.trace',
      timestamp: Date.now(),
      nodeId: 'node-1',
      queryText: SAMPLE_QUERIES[Math.floor(Math.random() * SAMPLE_QUERIES.length)],
      cognitiveProfile: profile,
      synapticTagMask: Math.floor(Math.random() * 0xFFFF),
      totalRecords: total,
      afterTombstone,
      afterTagGate,
      afterValence,
      afterDecay,
      afterVectorDistance: afterVector,
      finalTopK,
      hebbianActivated: Math.floor(Math.random() * 5),
      temporalLinked: Math.floor(Math.random() * 4),
      entityDiscovered: Math.floor(Math.random() * 3),
      latencyMicros: 800 + Math.floor(Math.random() * 4000),
    };

    this.state.pushQueryTrace(event);

    // Update query vector in embedding space
    this.state.queryVector.set([
      (Math.random() - 0.5) * 40,
      (Math.random() - 0.5) * 40,
      (Math.random() - 0.5) * 40,
    ]);

    // Randomly shift Zeigarnik
    if (Math.random() > 0.7) {
      this.state.unresolvedCount.update(v => Math.max(0, v + (Math.random() > 0.5 ? 1 : -1)));
    }
    if (Math.random() > 0.9) {
      this.state.totalTaskCount.update(v => v + 1);
    }
  }

  private emitSimdEvent(): void {
    const is512 = Math.random() > 0.3;
    const laneWidth = is512 ? 16 : 8;
    const vectorsProcessed = [100, 500, 1000, 5000][Math.floor(Math.random() * 4)];
    const durationMicros = Math.floor(Math.random() * 5000) + 100;

    const event: SimdLaneEvent = {
      eventType: 'cortex.simd.lane',
      timestamp: Date.now(),
      nodeId: 'node-1',
      kernelName: KERNELS[Math.floor(Math.random() * KERNELS.length)],
      laneWidth,
      vectorsProcessed,
      durationMicros,
      fallbackNanos: Math.floor(Math.random() * 1000),
    };

    this.state.pushSimdEvent(event);
  }

  private emitMemoryDiag(): void {
    const base = this.state.memoryDiag();
    const jitter = (v: number, pct: number) =>
      Math.max(0, Math.floor(v * (1 + (Math.random() - 0.5) * pct)));

    const event: MemoryDiagnosticEvent = {
      eventType: 'cortex.memory.diagnostic',
      timestamp: Date.now(),
      nodeId: 'node-1',
      offHeapBytes: jitter(base?.offHeapBytes ?? 50_331_648, 0.05),
      pinnedBytes: jitter(base?.pinnedBytes ?? 16_777_216, 0.03),
      jvmHeapUsed: jitter(base?.jvmHeapUsed ?? 268_435_456, 0.10),
      jvmHeapMax: 1_073_741_824,
      gpuAllocated: jitter(base?.gpuAllocated ?? 12_884_901_888, 0.02),
      gpuFree: jitter(base?.gpuFree ?? 12_884_901_888, 0.02),
      softPageFaults: (base?.softPageFaults ?? 12000) + Math.floor(Math.random() * 50),
      hardPageFaults: (base?.hardPageFaults ?? 3) + (Math.random() > 0.95 ? 1 : 0),
      workingCount: jitter(base?.workingCount ?? 45, 0.15),
      episodicCount: jitter(base?.episodicCount ?? 12500, 0.02),
      semanticCount: jitter(base?.semanticCount ?? 85000, 0.01),
      proceduralCount: jitter(base?.proceduralCount ?? 3200, 0.03),
      hebbianEdges: jitter(base?.hebbianEdges ?? 245000, 0.01),
      temporalLinks: jitter(base?.temporalLinks ?? 98000, 0.02),
      entityNodes: jitter(base?.entityNodes ?? 15000, 0.01),
      entityEdges: jitter(base?.entityEdges ?? 42000, 0.02),
      coActivationPairs: jitter(base?.coActivationPairs ?? 8500, 0.03),
      stdpEdges: jitter(base?.stdpEdges ?? 3200, 0.04),
    };

    this.state.pushMemoryDiag(event);
  }

  private emitGraphPulse(): void {
    const nodesVisited = 5 + Math.floor(Math.random() * 20);
    const edgesTraversed = nodesVisited + Math.floor(Math.random() * 15);
    const maxDepth = 1 + Math.floor(Math.random() * 3);
    const durationMicros = Math.floor(Math.random() * 2000) + 50;

    const event: GraphPulseEvent = {
      eventType: 'cortex.graph.pulse',
      timestamp: Date.now(),
      nodeId: 'node-1',
      nodesVisited,
      edgesTraversed,
      maxDepth,
      durationMicros,
    };

    this.state.pushGraphPulse(event);
  }

  private emitReflect(): void {
    this.reflectCycleCounter++;
    const cycleId = `reflect-${this.reflectCycleCounter}`;
    const diag = this.state.memoryDiag();

    // Emit pre-reflect snapshot
    const preSnapshot: MemorySnapshotEvent = {
      eventType: 'cortex.memory.snapshot',
      timestamp: Date.now(),
      nodeId: 'node-1',
      phase: 'pre-reflect',
      reflectCycleId: cycleId,
      hebbianEdgeCount: diag?.hebbianEdges ?? 245000,
      temporalLinkCount: diag?.temporalLinks ?? 98000,
      entityNodeCount: diag?.entityNodes ?? 15000,
      entityEdgeCount: diag?.entityEdges ?? 42000,
      offHeapBytes: diag?.offHeapBytes ?? 50_331_648,
      tombstoneCount: Math.floor(Math.random() * 500),
      coActivationPairs: diag?.coActivationPairs ?? 8500,
      stdpEdges: diag?.stdpEdges ?? 3200,
    };
    this.state.pushMemorySnapshot(preSnapshot);

    const edgesDecayed = 50 + Math.floor(Math.random() * 200);
    const edgesRemoved = Math.floor(Math.random() * 20);

    const event: ReflectCycleEvent = {
      eventType: 'cortex.reflect.cycle',
      timestamp: Date.now(),
      nodeId: 'node-1',
      hebbianEdgesDecayed: edgesDecayed,
      hebbianEdgesRemoved: edgesRemoved,
      decayFactor: 0.95 + Math.random() * 0.04,
      durationMs: 10 + Math.floor(Math.random() * 50),
    };

    this.state.pushReflect(event);

    // Emit post-reflect snapshot (slightly reduced counts)
    const postSnapshot: MemorySnapshotEvent = {
      eventType: 'cortex.memory.snapshot',
      timestamp: Date.now(),
      nodeId: 'node-1',
      phase: 'post-reflect',
      reflectCycleId: cycleId,
      hebbianEdgeCount: preSnapshot.hebbianEdgeCount - edgesRemoved,
      temporalLinkCount: preSnapshot.temporalLinkCount - Math.floor(Math.random() * 50),
      entityNodeCount: preSnapshot.entityNodeCount - Math.floor(Math.random() * 5),
      entityEdgeCount: preSnapshot.entityEdgeCount - Math.floor(Math.random() * 30),
      offHeapBytes: preSnapshot.offHeapBytes - Math.floor(Math.random() * 500_000),
      tombstoneCount: preSnapshot.tombstoneCount + Math.floor(Math.random() * 10),
      coActivationPairs: preSnapshot.coActivationPairs - Math.floor(Math.random() * 20),
      stdpEdges: preSnapshot.stdpEdges - Math.floor(Math.random() * 10),
    };
    this.state.pushMemorySnapshot(postSnapshot);
  }

  private emitMetrics(): void {
    const history = this.state.queryHistory();
    const recentCount = history.filter(h => h.timestamp > Date.now() - 5000).length;

    const point: MetricsPoint = {
      timestamp: Date.now(),
      recallRate: recentCount * (0.8 + Math.random() * 0.4),
      rememberRate: Math.random() * 2,
      reinforceRate: Math.random() * 1.5,
      forgetRate: Math.random() * 0.5,
      avgLatencyMs: 0.8 + Math.random() * 4,
    };

    this.state.pushMetrics(point);
  }

  private updateHabituation(): void {
    const current = this.state.habituation();
    this.state.habituation.set({
      inhibitionOfReturn: Math.min(1, Math.max(0, current.inhibitionOfReturn + (Math.random() - 0.45) * 0.1)),
      semanticSatiation: Math.min(1, Math.max(0, current.semanticSatiation + (Math.random() - 0.48) * 0.08)),
      habituationPenalty: Math.min(1, Math.max(0, current.habituationPenalty + (Math.random() - 0.5) * 0.05)),
      activeSuppressions: Math.max(0, Math.floor(current.activeSuppressions + (Math.random() - 0.4) * 3)),
      satiationCacheSize: Math.max(0, Math.floor(current.satiationCacheSize + (Math.random() - 0.3) * 5)),
    });
  }

  private generateVectorSpace(): void {
    // Generate 300 points in a PCA-projected 3D embedding space with natural clusters
    const points: Array<{
      id: string; position: [number, number, number];
      tier: string; importance: number; label: string;
    }> = [];

    // Create semantic clusters
    const clusters = [
      { center: [20, 10, 5], tier: 'SEMANTIC', spread: 8 },
      { center: [-15, 20, -10], tier: 'SEMANTIC', spread: 7 },
      { center: [5, -25, 15], tier: 'EPISODIC', spread: 10 },
      { center: [-20, -10, -20], tier: 'EPISODIC', spread: 9 },
      { center: [0, 0, 0], tier: 'WORKING', spread: 5 },
      { center: [25, -15, -5], tier: 'PROCEDURAL', spread: 6 },
      { center: [-10, 5, 25], tier: 'PROCEDURAL', spread: 7 },
    ];

    for (let i = 0; i < 300; i++) {
      const cluster = clusters[Math.floor(Math.random() * clusters.length)];
      const gaussian = () => (Math.random() + Math.random() + Math.random() - 1.5) * 2;
      points.push({
        id: `mem-${i}`,
        position: [
          cluster.center[0] + gaussian() * cluster.spread,
          cluster.center[1] + gaussian() * cluster.spread,
          cluster.center[2] + gaussian() * cluster.spread,
        ] as [number, number, number],
        tier: cluster.tier,
        importance: 0.1 + Math.random() * 0.9,
        label: LABELS[i % LABELS.length],
      });
    }

    this.state.vectorPoints.set(points);
  }

  private generateDecayCurve(): void {
    const points: DecayPoint[] = [];
    for (let d = 0; d <= 30; d += 0.5) {
      const rawDecay = Math.exp(-0.15 * d); // Standard Ebbinghaus
      // LTP reconsolidation: recall events boost retention
      const recallEvents = Math.floor(d / 3); // roughly every 3 days
      const ltpBoost = recallEvents * 0.1;
      const ltpDecay = Math.min(1, rawDecay + ltpBoost * Math.exp(-0.05 * d));
      points.push({ ageDays: d, rawDecay, ltpDecay });
    }
    this.state.decayCurve.set(points);
  }

  private emitGpuKernel(): void {
    const kernelNames = ['cosine_similarity', 'dot_product', 'euclidean_dist', 'hnsw_neighbor_select', 'memcpy_h2d', 'memcpy_d2h'];
    const streamIndex = Math.floor(Math.random() * 2);
    const kernelName = kernelNames[Math.floor(Math.random() * kernelNames.length)];
    const isMemcpy = kernelName.startsWith('memcpy');

    const event: GpuKernelEvent = {
      eventType: 'cortex.gpu.kernel',
      timestamp: Date.now(),
      nodeId: 'node-1',
      streamIndex,
      kernelName,
      durationMicros: isMemcpy ? 5 + Math.floor(Math.random() * 50) : 20 + Math.floor(Math.random() * 500),
      gridDim: [
        isMemcpy ? 1 : 32 + Math.floor(Math.random() * 224),
        isMemcpy ? 1 : 1 + Math.floor(Math.random() * 4),
        1,
      ],
      blockDim: [
        isMemcpy ? 1 : 128 + [0, 128, 384][Math.floor(Math.random() * 3)],
        1,
        1,
      ],
      memoryTransferBytes: isMemcpy
        ? 1_048_576 + Math.floor(Math.random() * 50_000_000)
        : Math.floor(Math.random() * 1_000_000),
    };

    this.state.pushGpuKernel(event);
  }

  private emitClusterTopology(): void {
    const event: ClusterTopologyEvent = {
      eventType: 'cortex.cluster.topology',
      timestamp: Date.now(),
      nodeId: 'node-1',
      nodes: [
        {
          nodeId: 'node-1',
          status: 'active',
          shardCount: 8 + Math.floor(Math.random() * 4),
          memoryUsedBytes: 50_000_000 + Math.floor(Math.random() * 10_000_000),
          queryRate: 2 + Math.random() * 5,
        },
        {
          nodeId: 'node-2',
          status: Math.random() > 0.1 ? 'active' : 'draining',
          shardCount: 6 + Math.floor(Math.random() * 4),
          memoryUsedBytes: 40_000_000 + Math.floor(Math.random() * 15_000_000),
          queryRate: 1.5 + Math.random() * 4,
        },
        {
          nodeId: 'node-3',
          status: Math.random() > 0.05 ? 'active' : 'down',
          shardCount: 4 + Math.floor(Math.random() * 6),
          memoryUsedBytes: 30_000_000 + Math.floor(Math.random() * 20_000_000),
          queryRate: 1 + Math.random() * 3,
        },
      ],
      replicationLinks: [
        ['node-1', 'node-2'],
        ['node-1', 'node-3'],
        ['node-2', 'node-3'],
      ],
    };

    this.state.pushClusterTopology(event);
  }
}
