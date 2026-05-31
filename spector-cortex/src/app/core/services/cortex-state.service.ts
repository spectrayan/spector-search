// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Central State Service
// ═══════════════════════════════════════════════════════════════════════
// Signal-based reactive store for all dashboard state.
// Components read signals; services write to them.

import { Injectable, signal, computed } from '@angular/core';
import {
  QueryTraceEvent,
  SimdLaneEvent,
  MemoryDiagnosticEvent,
  GraphPulseEvent,
  ReflectCycleEvent,
} from '../models/cortex-events';
import { CognitiveProfile, ConnectionStatus } from '../models/memory-types';

/** Maximum number of historical query traces to retain. */
const MAX_QUERY_HISTORY = 50;
const MAX_GRAPH_PULSES = 100;
const MAX_METRICS_HISTORY = 120; // 2 min at 1s intervals

/** Time-series data point for metrics chart. */
export interface MetricsPoint {
  timestamp: number;
  recallRate: number;
  rememberRate: number;
  reinforceRate: number;
  forgetRate: number;
  avgLatencyMs: number;
}

/** Decay curve data point. */
export interface DecayPoint {
  ageDays: number;
  rawDecay: number;
  ltpDecay: number;     // with reconsolidation
}

/** Habituation state for the meter. */
export interface HabituationState {
  inhibitionOfReturn: number;   // 0-1, higher = more suppressed
  semanticSatiation: number;    // 0-1, higher = more saturated
  habituationPenalty: number;   // 0-1, current average penalty
  activeSuppressions: number;   // count of suppressed memory IDs
  satiationCacheSize: number;   // current LRU cache occupancy
}

/** Graph layer visibility toggles. */
export interface GraphLayerToggles {
  hebbian: boolean;
  temporal: boolean;
  entity: boolean;
  particles: boolean;
}

@Injectable({ providedIn: 'root' })
export class CortexStateService {

  // ── Connection ─────────────────────────────────────────────────────
  readonly connectionStatus = signal<ConnectionStatus>('disconnected');
  readonly selectedNode = signal<string>('local');
  readonly useMockData = signal<boolean>(true);

  // ── Query Trace ────────────────────────────────────────────────────
  readonly currentQueryTrace = signal<QueryTraceEvent | null>(null);
  readonly queryHistory = signal<QueryTraceEvent[]>([]);

  // ── SIMD ───────────────────────────────────────────────────────────
  readonly simdState = signal<SimdLaneEvent | null>(null);

  // ── Memory Diagnostics ─────────────────────────────────────────────
  readonly memoryDiag = signal<MemoryDiagnosticEvent | null>(null);

  // ── Graph Pulses ───────────────────────────────────────────────────
  readonly graphPulses = signal<GraphPulseEvent[]>([]);

  // ── Reflection ─────────────────────────────────────────────────────
  readonly lastReflect = signal<ReflectCycleEvent | null>(null);

  // ── Cognitive Profile ──────────────────────────────────────────────
  readonly activeProfile = signal<CognitiveProfile>(CognitiveProfile.BALANCED);

  // ── Graph Layer Toggles ────────────────────────────────────────────
  readonly graphLayers = signal<GraphLayerToggles>({
    hebbian: true, temporal: true, entity: true, particles: true,
  });

  // ── Live Metrics Time-Series ───────────────────────────────────────
  readonly metricsHistory = signal<MetricsPoint[]>([]);

  // ── Decay Curve ────────────────────────────────────────────────────
  readonly decayCurve = signal<DecayPoint[]>([]);

  // ── Habituation ────────────────────────────────────────────────────
  readonly habituation = signal<HabituationState>({
    inhibitionOfReturn: 0, semanticSatiation: 0, habituationPenalty: 1,
    activeSuppressions: 0, satiationCacheSize: 0,
  });

  // ── Zeigarnik ──────────────────────────────────────────────────────
  readonly unresolvedCount = signal<number>(0);
  readonly totalTaskCount = signal<number>(0);

  // ── Vector Space ───────────────────────────────────────────────────
  readonly vectorPoints = signal<Array<{
    id: string;
    position: [number, number, number];
    tier: string;
    importance: number;
    label: string;
  }>>([]);
  readonly queryVector = signal<[number, number, number] | null>(null);

  // ── Query Input ────────────────────────────────────────────────────
  readonly isQueryRunning = signal<boolean>(false);

  // ── Computed ───────────────────────────────────────────────────────
  readonly isConnected = computed(() => this.connectionStatus() === 'connected');

  readonly totalMemoryCount = computed(() => {
    const diag = this.memoryDiag();
    if (!diag) return 0;
    return diag.workingCount + diag.episodicCount + diag.semanticCount + diag.proceduralCount;
  });

  readonly latestLatencyMs = computed(() => {
    const trace = this.currentQueryTrace();
    return trace ? (trace.latencyMicros / 1000).toFixed(2) : '—';
  });

  readonly zeigarnikPercentage = computed(() => {
    const total = this.totalTaskCount();
    const unresolved = this.unresolvedCount();
    return total > 0 ? Math.round((unresolved / total) * 100) : 0;
  });

  readonly avgLatency = computed(() => {
    const history = this.queryHistory();
    if (history.length === 0) return 0;
    const sum = history.reduce((a, b) => a + b.latencyMicros, 0);
    return sum / history.length / 1000;
  });

  // ── Mutations (called by services, not components) ─────────────────

  pushQueryTrace(event: QueryTraceEvent): void {
    this.currentQueryTrace.set(event);
    this.queryHistory.update(history => {
      const next = [event, ...history];
      return next.length > MAX_QUERY_HISTORY ? next.slice(0, MAX_QUERY_HISTORY) : next;
    });
    // Auto-detect profile from event
    if (event.cognitiveProfile) {
      const profile = event.cognitiveProfile as CognitiveProfile;
      if (Object.values(CognitiveProfile).includes(profile)) {
        this.activeProfile.set(profile);
      }
    }
  }

  pushSimdEvent(event: SimdLaneEvent): void {
    this.simdState.set(event);
  }

  pushMemoryDiag(event: MemoryDiagnosticEvent): void {
    this.memoryDiag.set(event);
  }

  pushGraphPulse(event: GraphPulseEvent): void {
    this.graphPulses.update(pulses => {
      const next = [event, ...pulses];
      return next.length > MAX_GRAPH_PULSES ? next.slice(0, MAX_GRAPH_PULSES) : next;
    });
  }

  pushReflect(event: ReflectCycleEvent): void {
    this.lastReflect.set(event);
  }

  pushMetrics(point: MetricsPoint): void {
    this.metricsHistory.update(history => {
      const next = [...history, point];
      return next.length > MAX_METRICS_HISTORY ? next.slice(-MAX_METRICS_HISTORY) : next;
    });
  }

  toggleGraphLayer(layer: keyof GraphLayerToggles): void {
    this.graphLayers.update(l => ({ ...l, [layer]: !l[layer] }));
  }
}
