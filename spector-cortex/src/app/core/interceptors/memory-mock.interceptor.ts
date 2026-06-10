// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Memory Mock Interceptor
// ═══════════════════════════════════════════════════════════════════════
// Intercepts memory API calls and returns realistic mock data when the
// backend is not running. Registered via withInterceptors() in app.config.

import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { of, delay } from 'rxjs';

// ── Realistic memory texts ──
const MEMORY_TEXTS: string[] = [
  'Kubernetes pods should be configured with resource limits to prevent noisy-neighbor issues in shared clusters',
  'The team decided to migrate from PostgreSQL to CockroachDB for multi-region replication support',
  'Alice mentioned that the OAuth2 token refresh flow has a race condition when multiple tabs are open',
  'Implement retry logic with exponential backoff for all external API calls — max 3 retries with jitter',
  'The CI pipeline takes 47 minutes on average; parallelizing integration tests could cut it to ~18 minutes',
  'React Server Components eliminate the need for getServerSideProps but require careful boundary placement',
  'Bob prefers functional error handling with Result types over try-catch for domain logic',
  'The memory heatmap visualization should use a logarithmic scale for segment utilization',
  'gRPC streaming outperforms REST polling for real-time dashboard updates — measured 3x lower latency',
  'Database connection pool exhaustion caused the P99 latency spike last Thursday at 14:32 UTC',
  'The Hebbian learning rule strengthens associations between memories that are recalled together frequently',
  'Vector embeddings should be normalized to unit length before cosine similarity computation',
  'Carol suggested using HNSW index instead of IVF-Flat for approximate nearest neighbor search at scale',
  'The consolidation daemon should run during low-traffic windows to minimize impact on recall latency',
  'Prometheus scrape interval of 15s is too coarse for detecting sub-second latency spikes',
  'TypeScript strict mode catches 23% more bugs at compile time according to the internal audit',
  'The off-heap memory allocator uses Panama Foreign Function API for zero-copy buffer management',
  'Sprint retrospective: deployment frequency improved from weekly to daily after adopting trunk-based development',
  'Entity graph merge detected "kubernets" → "kubernetes" typo correction with Levenshtein distance 1',
  'The Zeigarnik effect keeps unresolved tasks salient in working memory until explicitly marked complete',
  'Dave reported that the search relevance dropped after the embedding model was updated to v3',
  'Implement circuit breaker pattern for the LLM inference endpoint — 50% error rate threshold',
  'The temporal chain links memories in chronological order for narrative reconstruction during recall',
  'Cache invalidation strategy: use event-driven TTL with write-through for hot keys',
  'The SIMD-accelerated dot product is 8x faster than scalar on AVX-512 hardware',
  'Meeting notes: Q3 OKR is to reduce p50 search latency from 12ms to 5ms',
  'Semantic memory tier stores long-term factual knowledge that survives reflection cycles',
  'The decay curve follows Ebbinghaus forgetting function: R = e^(-t/S) where S is stability',
  'Feature flag "enable-graph-traversal" should be rolled out to 10% of traffic first',
  'Working memory is limited to ~7±2 items — implement automatic promotion to episodic after threshold',
];

const TIERS = ['WORKING', 'EPISODIC', 'SEMANTIC', 'PROCEDURAL'];
const SOURCES = ['USER_STATED', 'OBSERVED', 'INFERRED', 'EXTRACTED'];
const TAG_POOL = [
  'architecture', 'database', 'kubernetes', 'performance', 'security',
  'testing', 'deployment', 'ml', 'frontend', 'backend', 'devops',
  'monitoring', 'caching', 'api', 'concurrency', 'memory', 'search',
];

/** Generate a deterministic UUID-like ID from an index. */
function mockId(index: number): string {
  const hex = index.toString(16).padStart(8, '0');
  return `mem-${hex.slice(0, 4)}-${hex.slice(4)}-${(index * 7 + 3).toString(16).padStart(4, '0')}-${(index * 13 + 5).toString(16).padStart(4, '0')}`;
}

/** Seeded pseudo-random for deterministic mock data. */
function seededRandom(seed: number): number {
  const x = Math.sin(seed * 9301 + 49297) * 49297;
  return x - Math.floor(x);
}

/** Generate N mock memory rows. */
function generateMockRows(count: number): any[] {
  const now = Date.now();
  const rows: any[] = [];

  for (let i = 0; i < count; i++) {
    const r = seededRandom(i);
    const text = MEMORY_TEXTS[i % MEMORY_TEXTS.length];
    const tier = TIERS[Math.floor(seededRandom(i + 100) * TIERS.length)];
    const source = SOURCES[Math.floor(seededRandom(i + 200) * SOURCES.length)];
    const importance = parseFloat((0.1 + seededRandom(i + 300) * 0.9).toFixed(3));
    const valence = Math.floor(seededRandom(i + 400) * 256 - 128);
    const arousal = Math.floor(seededRandom(i + 500) * 256);
    const ageMs = Math.floor(seededRandom(i + 600) * 7 * 24 * 3600 * 1000); // up to 7 days
    const recallCount = Math.floor(seededRandom(i + 700) * 20);
    const tombstoned = seededRandom(i + 800) < 0.08;
    const pinned = seededRandom(i + 900) < 0.12;
    const resolved = seededRandom(i + 1000) < 0.15;
    const consolidated = tier === 'SEMANTIC' || tier === 'PROCEDURAL' || seededRandom(i + 1100) < 0.3;
    const suppressed = seededRandom(i + 1200) < 0.05;

    // Pick 1–3 random tags
    const tagCount = 1 + Math.floor(seededRandom(i + 1300) * 3);
    const tags: string[] = [];
    for (let t = 0; t < tagCount; t++) {
      const tag = TAG_POOL[Math.floor(seededRandom(i * 10 + t + 1400) * TAG_POOL.length)];
      if (!tags.includes(tag)) tags.push(tag);
    }

    rows.push({
      id: mockId(i),
      text,
      textPreview: text.length > 80 ? text.substring(0, 80) + '…' : text,
      tier,
      source,
      importance,
      valence,
      arousal,
      timestampMs: now - ageMs,
      agentRecallCount: recallCount,
      recallCount,
      tombstoned,
      pinned,
      resolved,
      consolidated,
      suppressed,
      tags,
      synapticTags: Math.floor(seededRandom(i + 1500) * 0xFFFFFFFF),
      createdAt: new Date(now - ageMs).toISOString(),
    });
  }

  return rows;
}

// Pre-generate 120 rows
const ALL_ROWS = generateMockRows(120);

/** Mock interceptor for memory API endpoints. */
export const memoryMockInterceptor: HttpInterceptorFn = (req, next) => {
  const url = req.url;

  // ── GET /api/v1/memory/table ──
  if (req.method === 'GET' && url.includes('/api/v1/memory/table')) {
    const params = req.params;
    const page = parseInt(params.get('page') ?? '0', 10);
    const pageSize = parseInt(params.get('pageSize') ?? '50', 10);
    const showTombstoned = params.get('tombstoned') === 'true';
    const tierFilter = params.get('tier');

    let filtered = ALL_ROWS.filter(r => showTombstoned || !r.tombstoned);
    if (tierFilter) {
      filtered = filtered.filter(r => r.tier === tierFilter);
    }

    const totalCount = filtered.length;
    const start = page * pageSize;
    const rows = filtered.slice(start, start + pageSize);

    const tierCounts: Record<string, number> = { WORKING: 0, EPISODIC: 0, SEMANTIC: 0, PROCEDURAL: 0 };
    const tombstoneRatios: Record<string, number> = { WORKING: 0, EPISODIC: 0, SEMANTIC: 0, PROCEDURAL: 0 };
    for (const r of ALL_ROWS) {
      tierCounts[r.tier] = (tierCounts[r.tier] ?? 0) + 1;
    }
    const tombstonedByTier: Record<string, number> = { WORKING: 0, EPISODIC: 0, SEMANTIC: 0, PROCEDURAL: 0 };
    for (const r of ALL_ROWS) {
      if (r.tombstoned) tombstonedByTier[r.tier] = (tombstonedByTier[r.tier] ?? 0) + 1;
    }
    for (const tier of Object.keys(tierCounts)) {
      tombstoneRatios[tier] = tierCounts[tier] > 0 ? tombstonedByTier[tier] / tierCounts[tier] : 0;
    }

    return of(new HttpResponse({
      status: 200,
      body: { rows, totalCount, page, pageSize, tierCounts, tombstoneRatios },
    })).pipe(delay(150));
  }

  // ── GET /api/v1/memory/status ──
  if (req.method === 'GET' && url.includes('/api/v1/memory/status')) {
    const tierCounts: Record<string, number> = { WORKING: 0, EPISODIC: 0, SEMANTIC: 0, PROCEDURAL: 0 };
    for (const r of ALL_ROWS) {
      tierCounts[r.tier] = (tierCounts[r.tier] ?? 0) + 1;
    }

    return of(new HttpResponse({
      status: 200,
      body: {
        totalMemories: ALL_ROWS.length,
        tierCounts,
        hebbianEdges: 2_847,
        temporalLinks: 1_203,
        entityNodes: 456,
        entityEdges: 1_892,
      },
    })).pipe(delay(100));
  }

  // ── POST /api/v1/memory/remember ──
  if (req.method === 'POST' && url.includes('/api/v1/memory/remember')) {
    const body = req.body as any;
    const newRow = {
      id: body.id ?? mockId(ALL_ROWS.length),
      text: body.text,
      textPreview: body.text?.substring(0, 80) + '…',
      tier: body.tier ?? 'WORKING',
      source: body.source ?? 'USER_STATED',
      importance: 0.5,
      valence: body.valence ?? 0,
      arousal: body.arousal ?? 128,
      timestampMs: Date.now(),
      agentRecallCount: 0,
      recallCount: 0,
      tombstoned: false,
      pinned: false,
      resolved: false,
      consolidated: false,
      suppressed: false,
      tags: body.tags ? body.tags.split(',') : [],
      synapticTags: 0,
      createdAt: new Date().toISOString(),
    };
    ALL_ROWS.unshift(newRow);

    return of(new HttpResponse({
      status: 200,
      body: newRow.id,
    })).pipe(delay(200));
  }

  // ── POST /api/v1/memory/{id}/reinforce ──
  if (req.method === 'POST' && url.match(/\/api\/v1\/memory\/[^/]+\/reinforce/)) {
    return of(new HttpResponse({ status: 200, body: 'OK' })).pipe(delay(100));
  }

  // ── POST /api/v1/memory/{id}/suppress ──
  if (req.method === 'POST' && url.match(/\/api\/v1\/memory\/[^/]+\/suppress/)) {
    return of(new HttpResponse({ status: 200, body: 'OK' })).pipe(delay(100));
  }

  // ── POST /api/v1/memory/{id}/resolve ──
  if (req.method === 'POST' && url.match(/\/api\/v1\/memory\/[^/]+\/resolve/)) {
    return of(new HttpResponse({ status: 200, body: 'OK' })).pipe(delay(100));
  }

  // ── POST /api/v1/memory/vacuum ──
  if (req.method === 'POST' && url.includes('/api/v1/memory/vacuum')) {
    return of(new HttpResponse({
      status: 200,
      body: { tier: 'SEMANTIC', beforeCount: 84, afterCount: 78, tombstonesRemoved: 6, bytesReclaimed: 49152, durationMs: 23 },
    })).pipe(delay(300));
  }

  // ── POST /api/v1/memory/reflect ──
  if (req.method === 'POST' && url.includes('/api/v1/memory/reflect')) {
    return of(new HttpResponse({
      status: 200,
      body: { promoted: 4, decayed: 12, merged: 2, prunedEdges: 8, durationMs: 187 },
    })).pipe(delay(500));
  }

  // Pass through to real backend
  return next(req);
};
