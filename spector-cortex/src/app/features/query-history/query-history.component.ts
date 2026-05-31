import { Component, inject, computed } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { PROFILE_PARAMS, CognitiveProfile } from '../../core/models/memory-types';

@Component({
  selector: 'cortex-query-history',
  imports: [MatIconModule, MatTooltipModule],
  templateUrl: './query-history.component.html',
  styleUrl: './query-history.component.scss',
})
export class QueryHistoryComponent {

  protected readonly state = inject(CortexStateService);
  protected readonly profileParams = PROFILE_PARAMS;

  protected readonly history = computed(() => {
    return this.state.queryHistory().map(trace => ({
      text: trace.queryText,
      profile: this.profileParams[trace.cognitiveProfile as CognitiveProfile]?.label ?? trace.cognitiveProfile,
      latencyMs: (trace.latencyMicros / 1000).toFixed(1),
      topK: trace.finalTopK,
      augmented: trace.hebbianActivated + trace.temporalLinked + trace.entityDiscovered,
      timestamp: trace.timestamp,
      totalRecords: trace.totalRecords,
    }));
  });

  protected formatTime(ts: number): string {
    const d = new Date(ts);
    return d.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }
}
