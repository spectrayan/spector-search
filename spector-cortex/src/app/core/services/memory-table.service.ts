import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/** A single memory row from the backend. */
export interface MemoryRow {
  id: string;
  text: string;
  textPreview: string;
  tier: string;
  source: string;
  importance: number;
  valence: number;
  arousal: number;
  timestampMs: number;
  agentRecallCount: number;
  recallCount: number;
  tombstoned: boolean;
  suppressed: boolean;
  pinned: boolean;
  resolved: boolean;
  consolidated: boolean;
  tags: string[];
  synapticTags: number;
  createdAt: string;
}

/** Paginated table response from the backend. */
export interface MemoryTableResponse {
  rows: MemoryRow[];
  totalCount: number;
  page: number;
  pageSize: number;
  tierCounts: Record<string, number>;
  tombstoneRatios: Record<string, number>;
}

/** Compaction result from vacuum endpoint. */
export interface CompactionResult {
  tier: string;
  beforeCount: number;
  afterCount: number;
  tombstonesRemoved: number;
  bytesReclaimed: number;
  durationMs: number;
}

/** Request DTO for remember. */
export interface RememberRequest {
  id: string;
  text: string;
  tier?: string;
  source?: string;
  tags?: string;
  interest?: number;
  challenge?: number;
  urgency?: number;
  valence?: number;
  arousal?: number;
}

/** Memory status from backend. */
export interface MemoryStatus {
  totalMemories: number;
  tierCounts: Record<string, number>;
  hebbianEdges: number;
  temporalLinks: number;
  entityNodes: number;
  entityEdges: number;
}

/** Score breakdown from the recall pipeline. */
export interface ScoreBreakdown {
  similarity: number;
  importanceDecay: number;
  tagBoostFactor: number;
  habituationPenalty: number;
  graphBoost: number;
  valenceAlignment: number;
  finalScore: number;
}

/** A single recall result from the backend. */
export interface RecallResult {
  id: string;
  text: string;
  score: number;
  importance: number;
  ageDays: number;
  agentRecallCount: number;
  valence: number;
  memoryType: string;
  source: string;
  synapticTags: string[];
  breakdown: ScoreBreakdown;
  lateral: boolean;
  negativeOutcome: boolean;
  hyperfocused: boolean;
  positivelyReinforced: boolean;
}

/** Full recall response from the backend. */
export interface RecallResponse {
  results: RecallResult[];
  totalMemories: number;
  queryTimeMs: number;
  profile: string;
}

/** A node in the memory graph. */
export interface GraphNode {
  id: string;
  tier: string;
  textPreview: string;
  importance: number;
  valence: number;
  entityNames?: string[];
}

/** An edge in the memory graph. */
export interface GraphEdge {
  fromId: string;
  toId: string;
  type: 'HEBBIAN' | 'TEMPORAL' | 'ENTITY';
  relation: string | null;
  weight: number;
  fromEntityType?: string;
  toEntityType?: string;
}

/** Graph response from the backend. */
export interface MemoryGraphResponse {
  memoryId: string | null;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

@Injectable({ providedIn: 'root' })
export class MemoryTableService {
  private readonly http = inject(HttpClient);
  private readonly API = '/api/v1/memory';

  // ── State signals ──
  readonly page = signal(0);
  readonly pageSize = signal(50);
  readonly tierFilter = signal<string | null>(null);
  readonly showTombstoned = signal(false);
  readonly sortField = signal<string>('timestampMs');
  readonly sortDirection = signal<'asc' | 'desc'>('desc');

  // ── Data signals ──
  readonly rows = signal<MemoryRow[]>([]);
  readonly totalCount = signal(0);
  readonly tierCounts = signal<Record<string, number>>({});
  readonly tombstoneRatios = signal<Record<string, number>>({});
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  /** Derived: total pages */
  readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalCount() / this.pageSize()))
  );

  // ══════════════════════════════════════════════════════════════
  // READ
  // ══════════════════════════════════════════════════════════════

  /** Fetches a page of memory rows from the backend. */
  loadPage(): void {
    this.loading.set(true);
    this.error.set(null);

    let params = new HttpParams()
      .set('page', this.page().toString())
      .set('pageSize', this.pageSize().toString())
      .set('tombstoned', this.showTombstoned().toString());

    const tier = this.tierFilter();
    if (tier) {
      params = params.set('tier', tier);
    }

    this.http.get<MemoryTableResponse>(`${this.API}/table`, { params })
      .subscribe({
        next: (resp) => {
          const sorted = this.sortRows(resp.rows);
          this.rows.set(sorted);
          this.totalCount.set(resp.totalCount);
          this.tierCounts.set(resp.tierCounts);
          this.tombstoneRatios.set(resp.tombstoneRatios);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err.message || 'Failed to load memory table');
          this.loading.set(false);
        },
      });
  }

  /** Returns a table page as an Observable (for use by detail view). */
  getTable(page: number, pageSize: number, tier: string, tombstoned: boolean): Observable<MemoryTableResponse> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('pageSize', pageSize.toString())
      .set('tombstoned', tombstoned.toString());
    if (tier) params = params.set('tier', tier);
    return this.http.get<MemoryTableResponse>(`${this.API}/table`, { params });
  }

  /** Returns full detail for a single memory by ID. */
  getMemoryById(id: string): Observable<MemoryRow> {
    return this.http.get<MemoryRow>(`${this.API}/${id}`);
  }

  /** Returns memory system status. */
  getStatus(): Observable<MemoryStatus> {
    return this.http.get<MemoryStatus>(`${this.API}/status`);
  }

  // ══════════════════════════════════════════════════════════════
  // GRAPH API
  // ══════════════════════════════════════════════════════════════

  /** Returns the graph neighborhood for a specific memory. */
  getMemoryGraph(id: string, depth: number = 2): Observable<MemoryGraphResponse> {
    const params = new HttpParams().set('depth', depth.toString());
    return this.http.get<MemoryGraphResponse>(`${this.API}/${id}/graph`, { params });
  }

  /** Returns a sampled overview of the entire memory graph. */
  getGraphOverview(maxNodes: number = 100): Observable<MemoryGraphResponse> {
    const params = new HttpParams().set('maxNodes', maxNodes.toString());
    return this.http.get<MemoryGraphResponse>(`${this.API}/graph/overview`, { params });
  }

  // ══════════════════════════════════════════════════════════════
  // WRITE — CRUD
  // ══════════════════════════════════════════════════════════════

  /** Stores a new memory. */
  remember(request: RememberRequest): Observable<string> {
    return this.http.post(`${this.API}/remember`, request, { responseType: 'text' });
  }

  /** Tombstones (soft-deletes) a memory. */
  forget(id: string): Observable<string> {
    return this.http.delete(`${this.API}/${id}`, { responseType: 'text' });
  }

  /** Reinforces a memory's recall weight. */
  reinforce(id: string, valence: number = 0): Observable<string> {
    return this.http.post(`${this.API}/${id}/reinforce`, { valence }, { responseType: 'text' });
  }

  /** Suppresses a memory from recall results. */
  suppress(id: string, reason: string = ''): Observable<string> {
    return this.http.post(`${this.API}/${id}/suppress`, { action: 'SUPPRESS', reason }, { responseType: 'text' });
  }

  /** Unsuppresses a memory. */
  unsuppress(id: string): Observable<string> {
    return this.http.post(`${this.API}/${id}/suppress`, { action: 'UNSUPPRESS' }, { responseType: 'text' });
  }

  /** Marks a memory as resolved. */
  resolve(id: string): Observable<string> {
    return this.http.post(`${this.API}/${id}/resolve`, { resolved: true }, { responseType: 'text' });
  }

  /** Marks a memory as unresolved. */
  unresolve(id: string): Observable<string> {
    return this.http.post(`${this.API}/${id}/resolve`, { resolved: false }, { responseType: 'text' });
  }

  // ══════════════════════════════════════════════════════════════
  // ADMIN
  // ══════════════════════════════════════════════════════════════

  /** Triggers vacuum compaction for a tier (returns Observable). */
  vacuum(tier: string): Observable<any> {
    return this.http.post(`${this.API}/vacuum`, { tier });
  }

  /** Triggers reflect consolidation cycle. */
  reflect(): Observable<any> {
    return this.http.post(`${this.API}/reflect`, {});
  }

  // ══════════════════════════════════════════════════════════════
  // RECALL — Cognitive Query
  // ══════════════════════════════════════════════════════════════

  /** Executes a cognitive recall query against the memory subsystem. */
  recall(query: string, topK: number = 10, profile?: string): Observable<RecallResponse> {
    const body: Record<string, any> = { query, topK };
    if (profile) body['profile'] = profile;
    return this.http.post<RecallResponse>(`${this.API}/recall`, body);
  }

  // ══════════════════════════════════════════════════════════════
  // NAVIGATION / SORT
  // ══════════════════════════════════════════════════════════════

  /** Client-side sort of rows. */
  private sortRows(rows: MemoryRow[]): MemoryRow[] {
    const field = this.sortField();
    const dir = this.sortDirection() === 'asc' ? 1 : -1;

    return [...rows].sort((a, b) => {
      const va = (a as any)[field];
      const vb = (b as any)[field];
      if (typeof va === 'number' && typeof vb === 'number') {
        return (va - vb) * dir;
      }
      return String(va).localeCompare(String(vb)) * dir;
    });
  }

  /** Navigate to next page. */
  nextPage(): void {
    if (this.page() < this.totalPages() - 1) {
      this.page.update(p => p + 1);
      this.loadPage();
    }
  }

  /** Navigate to previous page. */
  prevPage(): void {
    if (this.page() > 0) {
      this.page.update(p => p - 1);
      this.loadPage();
    }
  }

  /** Set tier filter and reload. */
  setTierFilter(tier: string | null): void {
    this.tierFilter.set(tier);
    this.page.set(0);
    this.loadPage();
  }

  /** Toggle tombstoned visibility and reload. */
  toggleTombstoned(): void {
    this.showTombstoned.update(v => !v);
    this.page.set(0);
    this.loadPage();
  }

  /** Set sort and reload. */
  setSort(field: string): void {
    if (this.sortField() === field) {
      this.sortDirection.update(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      this.sortField.set(field);
      this.sortDirection.set('desc');
    }
    this.loadPage();
  }
}
