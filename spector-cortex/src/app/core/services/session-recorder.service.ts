// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Session Recorder Service
// ═══════════════════════════════════════════════════════════════════════
// Records all incoming cortex events with precise timestamps.
// Supports export to JSON and import from file for replay.

import { Injectable, signal, computed } from '@angular/core';
import { AnyCortexEvent } from '../models/cortex-events';

/** A recorded event with its original timestamp and relative offset. */
export interface RecordedEvent {
  readonly offsetMs: number;       // ms from recording start
  readonly event: AnyCortexEvent;
}

/** Metadata for a recorded session. */
export interface SessionMetadata {
  readonly recordedAt: string;     // ISO timestamp
  readonly durationMs: number;
  readonly eventCount: number;
  readonly nodeId: string;
}

/** Full recorded session (serializable to JSON). */
export interface RecordedSession {
  readonly version: 1;
  readonly metadata: SessionMetadata;
  readonly events: RecordedEvent[];
}

@Injectable({ providedIn: 'root' })
export class SessionRecorderService {

  // ── State ──────────────────────────────────────────────────────────
  readonly isRecording = signal(false);
  readonly recordedEvents = signal<RecordedEvent[]>([]);
  readonly recordingStartTime = signal<number>(0);

  readonly eventCount = computed(() => this.recordedEvents().length);
  readonly durationMs = computed(() => {
    if (!this.isRecording()) return 0;
    const events = this.recordedEvents();
    if (events.length === 0) return 0;
    return events[events.length - 1].offsetMs;
  });

  // ── Recording Controls ─────────────────────────────────────────────

  /** Start recording events. Clears any previous recording. */
  startRecording(): void {
    this.recordedEvents.set([]);
    this.recordingStartTime.set(Date.now());
    this.isRecording.set(true);
  }

  /** Stop recording and return the recorded session. */
  stopRecording(): RecordedSession {
    this.isRecording.set(false);
    const events = this.recordedEvents();
    const startTime = this.recordingStartTime();

    return {
      version: 1,
      metadata: {
        recordedAt: new Date(startTime).toISOString(),
        durationMs: events.length > 0 ? events[events.length - 1].offsetMs : 0,
        eventCount: events.length,
        nodeId: events.length > 0 ? events[0].event.nodeId : 'unknown',
      },
      events,
    };
  }

  /** Record a single event (called by state service or event stream). */
  recordEvent(event: AnyCortexEvent): void {
    if (!this.isRecording()) return;
    const offset = Date.now() - this.recordingStartTime();
    this.recordedEvents.update(list => [...list, { offsetMs: offset, event }]);
  }

  // ── Export / Import ────────────────────────────────────────────────

  /** Export current recording as a downloadable JSON file. */
  exportSession(session: RecordedSession): void {
    const json = JSON.stringify(session, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = url;
    link.download = `cortex-session-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.json`;
    link.click();

    URL.revokeObjectURL(url);
  }

  /** Import a session from a JSON file. Returns the parsed session or null on error. */
  async importSession(file: File): Promise<RecordedSession | null> {
    try {
      const text = await file.text();
      const session = JSON.parse(text) as RecordedSession;

      // Validate structure
      if (session.version !== 1 || !session.metadata || !Array.isArray(session.events)) {
        console.error('Invalid session file format');
        return null;
      }

      return session;
    } catch (e) {
      console.error('Failed to parse session file:', e);
      return null;
    }
  }

  // ── localStorage Persistence ──────────────────────────────────────

  private readonly STORAGE_PREFIX = 'cortex-session-';
  private readonly MAX_STORAGE_BYTES = 5 * 1024 * 1024; // 5MB

  /** Save a session to localStorage, evicting oldest if over 5MB. */
  saveToLocalStorage(session: RecordedSession): void {
    try {
      const key = this.STORAGE_PREFIX + session.metadata.recordedAt;
      const json = JSON.stringify(session);

      // Evict oldest sessions until we have room
      this.evictOldSessions(json.length);

      localStorage.setItem(key, json);
    } catch (e) {
      console.warn('Failed to save session to localStorage:', e);
    }
  }

  /** Load all saved session metadata from localStorage. */
  loadSavedSessions(): SessionMetadata[] {
    const sessions: SessionMetadata[] = [];
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && key.startsWith(this.STORAGE_PREFIX)) {
          const raw = localStorage.getItem(key);
          if (raw) {
            const session = JSON.parse(raw) as RecordedSession;
            sessions.push(session.metadata);
          }
        }
      }
      // Sort newest first
      sessions.sort((a, b) => new Date(b.recordedAt).getTime() - new Date(a.recordedAt).getTime());
    } catch (e) {
      console.warn('Failed to load sessions from localStorage:', e);
    }
    return sessions;
  }

  /** Load a specific session from localStorage. */
  loadFromLocalStorage(recordedAt: string): RecordedSession | null {
    try {
      const key = this.STORAGE_PREFIX + recordedAt;
      const raw = localStorage.getItem(key);
      if (raw) {
        return JSON.parse(raw) as RecordedSession;
      }
    } catch (e) {
      console.warn('Failed to load session from localStorage:', e);
    }
    return null;
  }

  /** Delete a specific session from localStorage. */
  deleteSavedSession(recordedAt: string): void {
    localStorage.removeItem(this.STORAGE_PREFIX + recordedAt);
  }

  /** Evict oldest sessions until we have room for newBytes. */
  private evictOldSessions(newBytes: number): void {
    const sessions = this.getSortedStorageKeys();
    let totalBytes = this.getTotalStorageBytes();

    while (totalBytes + newBytes > this.MAX_STORAGE_BYTES && sessions.length > 0) {
      const oldest = sessions.pop()!;
      const raw = localStorage.getItem(oldest);
      if (raw) {
        totalBytes -= raw.length * 2; // UTF-16 encoding
        localStorage.removeItem(oldest);
      }
    }
  }

  private getSortedStorageKeys(): string[] {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(this.STORAGE_PREFIX)) {
        keys.push(key);
      }
    }
    // Sort newest first (oldest at end for pop)
    return keys.sort();
  }

  private getTotalStorageBytes(): number {
    let total = 0;
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith(this.STORAGE_PREFIX)) {
        const raw = localStorage.getItem(key);
        if (raw) total += raw.length * 2; // UTF-16
      }
    }
    return total;
  }
}
