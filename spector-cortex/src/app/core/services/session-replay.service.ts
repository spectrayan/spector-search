// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Session Replay Service
// ═══════════════════════════════════════════════════════════════════════
// Replays a recorded cognitive session into the CortexStateService,
// preserving original timing with adjustable playback speed.

import { Injectable, inject, signal, computed, OnDestroy } from '@angular/core';
import { CortexStateService } from './cortex-state.service';
import { RecordedSession, RecordedEvent } from './session-recorder.service';
import {
  QueryTraceEvent,
  SimdLaneEvent,
  MemoryDiagnosticEvent,
  GraphPulseEvent,
  ReflectCycleEvent,
} from '../models/cortex-events';

/** Available playback speeds. */
export const PLAYBACK_SPEEDS = [0.25, 0.5, 1, 2, 4, 8] as const;
export type PlaybackSpeed = typeof PLAYBACK_SPEEDS[number];

@Injectable({ providedIn: 'root' })
export class SessionReplayService implements OnDestroy {

  private readonly state = inject(CortexStateService);

  // ── Replay State ───────────────────────────────────────────────────
  readonly isReplaying = signal(false);
  readonly isPaused = signal(false);
  readonly currentSession = signal<RecordedSession | null>(null);
  readonly currentIndex = signal(0);
  readonly playbackSpeed = signal<PlaybackSpeed>(1);

  readonly progress = computed(() => {
    const session = this.currentSession();
    if (!session || session.events.length === 0) return 0;
    return this.currentIndex() / session.events.length;
  });

  readonly currentTimeMs = computed(() => {
    const session = this.currentSession();
    if (!session || session.events.length === 0) return 0;
    const idx = Math.min(this.currentIndex(), session.events.length - 1);
    return session.events[idx].offsetMs;
  });

  readonly totalDurationMs = computed(() => {
    const session = this.currentSession();
    return session?.metadata.durationMs ?? 0;
  });

  readonly totalEvents = computed(() => {
    const session = this.currentSession();
    return session?.events.length ?? 0;
  });

  private replayTimer: ReturnType<typeof setTimeout> | null = null;

  // ── Replay Controls ────────────────────────────────────────────────

  /** Load a session for replay. Does not start playback. */
  loadSession(session: RecordedSession): void {
    this.stop();
    this.currentSession.set(session);
    this.currentIndex.set(0);
  }

  /** Start or resume playback. */
  play(): void {
    const session = this.currentSession();
    if (!session || session.events.length === 0) return;

    this.isReplaying.set(true);
    this.isPaused.set(false);
    this.state.connectionStatus.set('connected');
    this.scheduleNextEvent();
  }

  /** Pause playback at current position. */
  pause(): void {
    this.isPaused.set(true);
    this.clearTimer();
  }

  /** Stop playback and reset to beginning. */
  stop(): void {
    this.clearTimer();
    this.isReplaying.set(false);
    this.isPaused.set(false);
    this.currentIndex.set(0);
  }

  /** Step forward one event while paused. */
  stepForward(): void {
    const session = this.currentSession();
    if (!session) return;
    const idx = this.currentIndex();
    if (idx < session.events.length) {
      this.dispatchEvent(session.events[idx]);
      this.currentIndex.set(idx + 1);
    }
  }

  /** Seek to a specific position (0.0 – 1.0). */
  seekTo(fraction: number): void {
    const session = this.currentSession();
    if (!session) return;
    const targetIdx = Math.floor(fraction * session.events.length);
    this.currentIndex.set(Math.max(0, Math.min(targetIdx, session.events.length - 1)));

    if (this.isReplaying() && !this.isPaused()) {
      this.clearTimer();
      this.scheduleNextEvent();
    }
  }

  /** Change playback speed. */
  setSpeed(speed: PlaybackSpeed): void {
    this.playbackSpeed.set(speed);
    if (this.isReplaying() && !this.isPaused()) {
      this.clearTimer();
      this.scheduleNextEvent();
    }
  }

  ngOnDestroy(): void {
    this.clearTimer();
  }

  // ── Internal ───────────────────────────────────────────────────────

  private scheduleNextEvent(): void {
    const session = this.currentSession();
    if (!session) return;

    const idx = this.currentIndex();
    if (idx >= session.events.length) {
      // Replay complete
      this.isReplaying.set(false);
      this.isPaused.set(false);
      return;
    }

    const current = session.events[idx];

    // Calculate delay until next event
    let delayMs = 0;
    if (idx + 1 < session.events.length) {
      const next = session.events[idx + 1];
      delayMs = (next.offsetMs - current.offsetMs) / this.playbackSpeed();
    }

    // Dispatch current event immediately
    this.dispatchEvent(current);
    this.currentIndex.set(idx + 1);

    // Schedule next
    if (idx + 1 < session.events.length) {
      this.replayTimer = setTimeout(() => {
        if (this.isReplaying() && !this.isPaused()) {
          this.scheduleNextEvent();
        }
      }, Math.max(1, delayMs));
    } else {
      // Last event dispatched
      this.isReplaying.set(false);
    }
  }

  private dispatchEvent(recorded: RecordedEvent): void {
    const event = recorded.event;
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
    }
  }

  private clearTimer(): void {
    if (this.replayTimer !== null) {
      clearTimeout(this.replayTimer);
      this.replayTimer = null;
    }
  }
}
