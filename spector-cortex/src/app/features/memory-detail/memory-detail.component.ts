import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import { MemoryTableService, GraphNode, GraphEdge, MemoryGraphResponse } from '../../core/services/memory-table.service';

/** Tier color mapping for visual badges. */
const TIER_COLORS: Record<string, string> = {
  WORKING: '#ffb74d',
  EPISODIC: '#66bb6a',
  SEMANTIC: '#42a5f5',
  PROCEDURAL: '#ab47bc',
};

const EDGE_TYPE_ICONS: Record<string, string> = {
  HEBBIAN: 'link',
  TEMPORAL: 'timeline',
  ENTITY: 'category',
};

@Component({
  selector: 'cortex-memory-detail',
  standalone: true,
  imports: [
    RouterLink,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatProgressBarModule,
    MatTooltipModule,
    MatDividerModule,
    MatBadgeModule,
  ],
  templateUrl: './memory-detail.component.html',
  styleUrl: './memory-detail.component.scss',
})
export class MemoryDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly memoryService = inject(MemoryTableService);

  readonly memoryId = signal('');
  readonly memory = signal<any>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  // Graph state
  readonly graphData = signal<MemoryGraphResponse | null>(null);
  readonly graphLoading = signal(false);
  readonly graphError = signal<string | null>(null);

  readonly tierColor = computed(() => {
    const mem = this.memory();
    return mem ? (TIER_COLORS[mem.tier] ?? '#9e9e9e') : '#9e9e9e';
  });

  readonly importancePct = computed(() => {
    const mem = this.memory();
    return mem ? Math.round(mem.importance * 100) : 0;
  });

  readonly valenceLabel = computed(() => {
    const mem = this.memory();
    if (!mem) return 'Neutral';
    if (mem.valence > 50) return 'Positive';
    if (mem.valence < -50) return 'Negative';
    return 'Neutral';
  });

  readonly ageLabel = computed(() => {
    const mem = this.memory();
    if (!mem?.createdAt) return '';
    const ms = Date.now() - new Date(mem.createdAt).getTime();
    const hours = Math.floor(ms / 3_600_000);
    if (hours < 1) return 'Just now';
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
  });

  /** Group graph edges by type for display. */
  readonly hebbianEdges = computed(() => this.edgesOfType('HEBBIAN'));
  readonly temporalEdges = computed(() => this.edgesOfType('TEMPORAL'));
  readonly entityEdges = computed(() => this.edgesOfType('ENTITY'));

  /** Lookup a graph node by ID. */
  graphNodeById(id: string): GraphNode | undefined {
    return this.graphData()?.nodes.find(n => n.id === id);
  }

  /** Get tier color for a graph node. */
  nodeTierColor(node: GraphNode): string {
    return TIER_COLORS[node.tier] ?? '#9e9e9e';
  }

  /** Get edge type icon. */
  edgeTypeIcon(type: string): string {
    return EDGE_TYPE_ICONS[type] ?? 'link';
  }

  /** Get the neighbor ID from an edge (the one that isn't the focal memory). */
  neighborId(edge: GraphEdge): string {
    return edge.fromId === this.memoryId() ? edge.toId : edge.fromId;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.memoryId.set(id);
      this.loadMemory(id);
      this.loadGraph(id);
    }
  }

  private loadMemory(id: string): void {
    this.loading.set(true);
    this.memoryService.getMemoryById(id).subscribe({
      next: (row) => {
        // The backend returns full text in textPreview for the /{id} endpoint
        const detail = {
          ...row,
          text: row.textPreview,
          createdAt: row.timestampMs ? new Date(row.timestampMs).toISOString() : null,
        };
        this.memory.set(detail);
        this.loading.set(false);
      },
      error: (err) => {
        const msg = typeof err.error === 'string' ? err.error
                  : err.error?.message ?? err.message ?? 'Memory not found';
        this.error.set(msg);
        this.loading.set(false);
      },
    });
  }

  private loadGraph(id: string): void {
    this.graphLoading.set(true);
    this.graphError.set(null);

    this.memoryService.getMemoryGraph(id, 2).subscribe({
      next: (data) => {
        this.graphData.set(data);
        this.graphLoading.set(false);
      },
      error: (err) => {
        // err.error is the response body (string or object), err.message is the JS error
        const msg = typeof err.error === 'string' ? err.error
                  : err.error?.message ?? err.message ?? 'Unknown error';
        this.graphError.set(msg);
        this.graphLoading.set(false);
      },
    });
  }

  private edgesOfType(type: string): GraphEdge[] {
    const data = this.graphData();
    if (!data) return [];
    return data.edges.filter(e => e.type === type);
  }

  onReinforce(): void {
    this.memoryService.reinforce(this.memoryId()).subscribe({
      next: () => this.loadMemory(this.memoryId()),
    });
  }

  onSuppress(): void {
    this.memoryService.suppress(this.memoryId()).subscribe({
      next: () => this.loadMemory(this.memoryId()),
    });
  }

  onResolve(): void {
    this.memoryService.resolve(this.memoryId()).subscribe({
      next: () => this.loadMemory(this.memoryId()),
    });
  }

  onForget(): void {
    this.memoryService.forget(this.memoryId()).subscribe({
      next: () => this.router.navigate(['/memories']),
    });
  }

  goBack(): void {
    this.router.navigate(['/memories']);
  }
}
