import { Component, inject, computed } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { CortexStateService } from '../../core/services/cortex-state.service';

interface SegmentRow {
  name: string;
  tier: string;
  count: number;
  sizeLabel: string;
  intensity: number; // 0-1 heat
}

@Component({
  selector: 'cortex-memory-heatmap',
  imports: [DecimalPipe],
  templateUrl: './memory-heatmap.component.html',
  styleUrl: './memory-heatmap.component.scss',
})
export class MemoryHeatmapComponent {

  protected readonly state = inject(CortexStateService);

  protected readonly segments = computed<SegmentRow[]>(() => {
    const diag = this.state.memoryDiag();
    if (!diag) return [];

    const maxCount = Math.max(
      diag.workingCount, diag.episodicCount, diag.semanticCount,
      diag.proceduralCount, 1,
    );

    return [
      {
        name: 'Working Memory', tier: 'WORKING',
        count: diag.workingCount,
        sizeLabel: this.formatBytes(diag.workingCount * 164),
        intensity: Math.min(1, diag.workingCount / 100), // Working is always hot
      },
      {
        name: 'Episodic Memory', tier: 'EPISODIC',
        count: diag.episodicCount,
        sizeLabel: this.formatBytes(diag.episodicCount * 164),
        intensity: diag.episodicCount / maxCount * 0.7,
      },
      {
        name: 'Semantic Memory', tier: 'SEMANTIC',
        count: diag.semanticCount,
        sizeLabel: this.formatBytes(diag.semanticCount * 36), // header slab only
        intensity: diag.semanticCount / maxCount * 0.5,
      },
      {
        name: 'Procedural Memory', tier: 'PROCEDURAL',
        count: diag.proceduralCount,
        sizeLabel: this.formatBytes(diag.proceduralCount * 164),
        intensity: diag.proceduralCount / maxCount * 0.4,
      },
      {
        name: 'Hebbian Graph', tier: 'GRAPH',
        count: diag.hebbianEdges,
        sizeLabel: this.formatBytes(diag.hebbianEdges / 20 * 164), // 164B per node
        intensity: 0.6 + Math.random() * 0.2,
      },
      {
        name: 'Temporal Chain', tier: 'GRAPH',
        count: diag.temporalLinks,
        sizeLabel: this.formatBytes(diag.temporalLinks * 16),
        intensity: 0.3 + Math.random() * 0.2,
      },
      {
        name: 'Entity Graph', tier: 'GRAPH',
        count: diag.entityNodes,
        sizeLabel: this.formatBytes(diag.entityNodes * 64 + diag.entityEdges * 12),
        intensity: 0.4 + Math.random() * 0.2,
      },
      {
        name: 'CoActivation Pairs', tier: 'STDP',
        count: diag.coActivationPairs,
        sizeLabel: this.formatBytes(diag.coActivationPairs * 32),
        intensity: 0.2 + Math.random() * 0.15,
      },
      {
        name: 'STDP Edges', tier: 'STDP',
        count: diag.stdpEdges,
        sizeLabel: this.formatBytes(diag.stdpEdges * 40),
        intensity: 0.15 + Math.random() * 0.15,
      },
    ];
  });

  protected readonly jvmMetrics = computed(() => {
    const diag = this.state.memoryDiag();
    if (!diag) return null;
    return {
      heapUsed: this.formatBytes(diag.jvmHeapUsed),
      heapMax: this.formatBytes(diag.jvmHeapMax),
      heapPct: ((diag.jvmHeapUsed / diag.jvmHeapMax) * 100).toFixed(1),
      offHeap: this.formatBytes(diag.offHeapBytes),
      pinned: this.formatBytes(diag.pinnedBytes),
      softFaults: diag.softPageFaults,
      hardFaults: diag.hardPageFaults,
    };
  });

  protected getHeatColor(intensity: number): string {
    // Interpolate from cool (surface) to hot (primary)
    const pct = Math.round(Math.max(0, Math.min(1, intensity)) * 100);
    return `color-mix(in srgb, var(--mat-sys-primary) ${pct}%, var(--mat-sys-surface-container-highest))`;
  }

  private formatBytes(bytes: number): string {
    if (bytes >= 1_073_741_824) return (bytes / 1_073_741_824).toFixed(1) + ' GB';
    if (bytes >= 1_048_576) return (bytes / 1_048_576).toFixed(1) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return bytes + ' B';
  }
}
