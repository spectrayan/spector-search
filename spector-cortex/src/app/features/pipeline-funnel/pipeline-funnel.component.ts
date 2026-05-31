import { Component, inject, computed } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { CortexStateService } from '../../core/services/cortex-state.service';

interface FunnelPhase {
  label: string;
  count: number;
  filtered: number;
  percentage: number;
  barWidth: number;
}

@Component({
  selector: 'cortex-pipeline-funnel',
  imports: [DecimalPipe],
  templateUrl: './pipeline-funnel.component.html',
  styleUrl: './pipeline-funnel.component.scss',
})
export class PipelineFunnelComponent {

  protected readonly state = inject(CortexStateService);

  protected readonly phases = computed<FunnelPhase[]>(() => {
    const trace = this.state.currentQueryTrace();
    if (!trace) return [];

    const steps = [
      { label: 'Total Records', count: trace.totalRecords },
      { label: 'After Tombstone', count: trace.afterTombstone },
      { label: 'After Tag Gate', count: trace.afterTagGate },
      { label: 'After Valence', count: trace.afterValence },
      { label: 'After Decay', count: trace.afterDecay },
      { label: 'Vector Distance', count: trace.afterVectorDistance },
      { label: 'Final Top-K', count: trace.finalTopK },
    ];

    const max = steps[0].count || 1;

    return steps.map((step, i) => {
      const prev = i > 0 ? steps[i - 1].count : step.count;
      const filtered = prev - step.count;
      const percentage = prev > 0 ? (filtered / prev) * 100 : 0;
      const barWidth = Math.max(2, (step.count / max) * 100);

      return { ...step, filtered, percentage, barWidth };
    });
  });

  protected readonly augmentation = computed(() => {
    const trace = this.state.currentQueryTrace();
    if (!trace) return null;
    return {
      hebbian: trace.hebbianActivated,
      temporal: trace.temporalLinked,
      entity: trace.entityDiscovered,
      total: trace.hebbianActivated + trace.temporalLinked + trace.entityDiscovered,
    };
  });

  protected readonly queryText = computed(() => {
    return this.state.currentQueryTrace()?.queryText ?? '—';
  });

  protected formatCount(n: number): string {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return n.toString();
  }
}
