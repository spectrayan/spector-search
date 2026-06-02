// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Event Stream Service
// ═══════════════════════════════════════════════════════════════════════
// Real SSE client that connects to GET /api/v1/events?filter=cortex
// and routes incoming events to CortexStateService signals.
// Includes auto-reconnect with exponential backoff.

import { Injectable, inject, signal, OnDestroy, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CortexStateService } from './cortex-state.service';
import { SessionRecorderService } from './session-recorder.service';
import {
  QueryTraceEvent,
  SimdLaneEvent,
  MemoryDiagnosticEvent,
  GraphPulseEvent,
  ReflectCycleEvent,
  MemorySnapshotEvent,
  GpuKernelEvent,
  ClusterTopologyEvent,
  EmbeddingProjectionEvent,
  AnyCortexEvent,
} from '../models/cortex-events';

/** SSE connection config. */
export interface EventStreamConfig {
  baseUrl: string;       // e.g. 'http://localhost:4200/api/v1'
  filter: string;        // e.g. 'cortex'
  maxRetries: number;
  initialRetryMs: number;
}

const DEFAULT_CONFIG: EventStreamConfig = {
  baseUrl: '/api/v1',
  filter: 'cortex',
  maxRetries: 10,
  initialRetryMs: 1000,
};

@Injectable({ providedIn: 'root' })
export class EventStreamService implements OnDestroy {

  private readonly state = inject(CortexStateService);
  private readonly recorder = inject(SessionRecorderService);
  private readonly platformId = inject(PLATFORM_ID);

  readonly isConnected = signal(false);
  readonly retryCount = signal(0);

  private eventSource: EventSource | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private config = DEFAULT_CONFIG;

  /** Connect to the SSE endpoint. */
  connect(config?: Partial<EventStreamConfig>): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.doConnect();
  }

  /** Disconnect and stop reconnection attempts. */
  disconnect(): void {
    this.clearRetryTimer();
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.isConnected.set(false);
    this.state.connectionStatus.set('disconnected');
  }

  ngOnDestroy(): void {
    this.disconnect();
  }

  // ── Internal ───────────────────────────────────────────────────────

  private doConnect(): void {
    const url = `${this.config.baseUrl}/events?filter=${this.config.filter}`;

    this.state.connectionStatus.set('reconnecting');
    this.eventSource = new EventSource(url);

    this.eventSource.onopen = () => {
      this.isConnected.set(true);
      this.retryCount.set(0);
      this.state.connectionStatus.set('connected');
    };

    this.eventSource.onerror = () => {
      this.isConnected.set(false);
      this.state.connectionStatus.set('disconnected');
      this.eventSource?.close();
      this.eventSource = null;
      this.scheduleReconnect();
    };

    // Register event handlers for each cortex event type
    this.registerEventHandler('cortex.query.trace');
    this.registerEventHandler('cortex.simd.lane');
    this.registerEventHandler('cortex.memory.diagnostic');
    this.registerEventHandler('cortex.graph.pulse');
    this.registerEventHandler('cortex.reflect.cycle');
    this.registerEventHandler('cortex.memory.snapshot');
    this.registerEventHandler('cortex.gpu.kernel');
    this.registerEventHandler('cortex.cluster.topology');
    this.registerEventHandler('cortex.embedding.projection');
  }

  private registerEventHandler(eventType: string): void {
    if (!this.eventSource) return;

    this.eventSource.addEventListener(eventType, (event: Event) => {
      const messageEvent = event as MessageEvent;
      try {
        const data = JSON.parse(messageEvent.data);
        data.eventType = eventType;
        this.dispatchEvent(data as AnyCortexEvent);
      } catch (e) {
        console.warn(`Failed to parse SSE event ${eventType}:`, e);
      }
    });
  }

  private dispatchEvent(event: AnyCortexEvent): void {
    // Record if recording is active
    this.recorder.recordEvent(event);

    switch (event.eventType) {
      case 'cortex.query.trace':
        this.state.pushQueryTrace(event as QueryTraceEvent);
        break;
      case 'cortex.simd.lane':
        this.state.pushSimdEvent(event as SimdLaneEvent);
        break;
      case 'cortex.memory.diagnostic':
        this.state.pushMemoryDiag(event as MemoryDiagnosticEvent);
        break;
      case 'cortex.graph.pulse':
        this.state.pushGraphPulse(event as GraphPulseEvent);
        break;
      case 'cortex.reflect.cycle':
        this.state.pushReflect(event as ReflectCycleEvent);
        break;
      case 'cortex.memory.snapshot':
        this.state.pushMemorySnapshot(event as MemorySnapshotEvent);
        break;
      case 'cortex.gpu.kernel':
        this.state.pushGpuKernel(event as GpuKernelEvent);
        break;
      case 'cortex.cluster.topology':
        this.state.pushClusterTopology(event as ClusterTopologyEvent);
        break;
      case 'cortex.embedding.projection':
        this.state.pushEmbeddingProjection(event as EmbeddingProjectionEvent);
        break;
    }
  }

  private scheduleReconnect(): void {
    const retry = this.retryCount();
    if (retry >= this.config.maxRetries) {
      console.error('EventStreamService: max retries reached, giving up');
      return;
    }

    // Exponential backoff: 1s, 2s, 4s, 8s, 16s, ... capped at 30s
    const delayMs = Math.min(
      this.config.initialRetryMs * Math.pow(2, retry),
      30_000,
    );

    this.state.connectionStatus.set('reconnecting');
    this.retryCount.update(r => r + 1);

    this.retryTimer = setTimeout(() => {
      this.doConnect();
    }, delayMs);
  }

  private clearRetryTimer(): void {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }
}
