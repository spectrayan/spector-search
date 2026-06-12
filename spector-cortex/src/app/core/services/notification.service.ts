// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Notification Service
// ═══════════════════════════════════════════════════════════════════════
// Tracks async ingestion tasks and provides notification state for the
// header bell icon. Fed by SSE ingestion.progress and ingestion.completed
// events from the EventStreamService.

import { Injectable, computed, signal } from '@angular/core';

/** A single notification entry (in-memory only — does not survive page refresh). */
export interface Notification {
  taskId: string;
  description: string;
  type: 'progress' | 'success' | 'error';
  progress: number;           // 0–100, or -1 if indeterminate
  chunksStored: number;
  totalChunks: number;
  failures: number;
  durationMs?: number;
  timestamp: Date;
  read: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {

  /** All notifications, newest first. */
  readonly notifications = signal<Notification[]>([]);

  /** Count of unread notifications. */
  readonly unreadCount = computed(() =>
    this.notifications().filter(n => !n.read).length
  );

  /** Whether there are any active (in-progress) tasks. */
  readonly hasActiveTasks = computed(() =>
    this.notifications().some(n => n.type === 'progress')
  );

  // ── Called by EventStreamService ──────────────────────────────────

  /** Updates or creates a progress notification for a task. */
  onProgress(event: {
    taskId: string;
    description: string;
    chunksStored: number;
    totalChunks: number;
    failures: number;
    progressPercent: number;
  }): void {
    this.notifications.update(list => {
      const existing = list.find(n => n.taskId === event.taskId);
      if (existing) {
        // Update in place
        return list.map(n =>
          n.taskId === event.taskId
            ? {
                ...n,
                progress: event.progressPercent,
                chunksStored: event.chunksStored,
                totalChunks: event.totalChunks,
                failures: event.failures,
                timestamp: new Date(),
              }
            : n
        );
      }
      // New task
      return [
        {
          taskId: event.taskId,
          description: event.description,
          type: 'progress' as const,
          progress: event.progressPercent,
          chunksStored: event.chunksStored,
          totalChunks: event.totalChunks,
          failures: event.failures,
          timestamp: new Date(),
          read: false,
        },
        ...list,
      ];
    });
  }

  /** Marks a task as completed (success or error). */
  onCompleted(event: {
    taskId: string;
    description: string;
    chunksStored: number;
    failures: number;
    durationMs: number;
    success: boolean;
  }): void {
    this.notifications.update(list => {
      const existing = list.find(n => n.taskId === event.taskId);
      if (existing) {
        return list.map(n =>
          n.taskId === event.taskId
            ? {
                ...n,
                type: (event.success ? 'success' : 'error') as 'success' | 'error',
                progress: 100,
                chunksStored: event.chunksStored,
                failures: event.failures,
                durationMs: event.durationMs,
                timestamp: new Date(),
                read: false,
              }
            : n
        );
      }
      // Task completed without a progress event (small tasks)
      return [
        {
          taskId: event.taskId,
          description: event.description,
          type: (event.success ? 'success' : 'error') as 'success' | 'error',
          progress: 100,
          chunksStored: event.chunksStored,
          totalChunks: event.chunksStored,
          failures: event.failures,
          durationMs: event.durationMs,
          timestamp: new Date(),
          read: false,
        },
        ...list,
      ];
    });
  }

  // ── UI Actions ────────────────────────────────────────────────────

  /** Marks all notifications as read. */
  markAllRead(): void {
    this.notifications.update(list =>
      list.map(n => ({ ...n, read: true }))
    );
  }

  /** Dismisses a single notification by taskId. */
  dismiss(taskId: string): void {
    this.notifications.update(list =>
      list.filter(n => n.taskId !== taskId)
    );
  }

  /** Clears all notifications. */
  clearAll(): void {
    this.notifications.set([]);
  }
}
