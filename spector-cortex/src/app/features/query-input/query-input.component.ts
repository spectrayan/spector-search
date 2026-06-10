import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { MemoryTableService } from '../../core/services/memory-table.service';

@Component({
  selector: 'cortex-query-input',
  imports: [FormsModule, MatIconModule],
  templateUrl: './query-input.component.html',
  styleUrl: './query-input.component.scss',
})
export class QueryInputComponent {

  protected readonly state = inject(CortexStateService);
  private readonly memoryService = inject(MemoryTableService);
  protected queryText = '';

  protected submitQuery(): void {
    if (!this.queryText.trim()) return;
    const query = this.queryText.trim();
    this.state.isQueryRunning.set(true);
    this.state.lastQueryText.set(query);

    this.memoryService.recall(query, 10).subscribe({
      next: (response) => {
        this.state.recallResults.set(response.results);
        this.state.recallQueryTimeMs.set(response.queryTimeMs);
        this.state.recallTotalMemories.set(response.totalMemories);
        this.state.recallProfile.set(response.profile);
        this.state.isQueryRunning.set(false);

        // Also push a synthetic query trace for the history panel
        this.state.pushQueryTrace({
          eventType: 'cortex.query.trace',
          timestamp: Date.now(),
          nodeId: 'local',
          queryText: query,
          cognitiveProfile: response.profile,
          synapticTagMask: 0,
          totalRecords: response.totalMemories,
          afterTombstone: response.totalMemories,
          afterTagGate: response.totalMemories,
          afterValence: response.totalMemories,
          afterDecay: response.totalMemories,
          afterVectorDistance: response.results.length,
          finalTopK: response.results.length,
          hebbianActivated: 0,
          temporalLinked: 0,
          entityDiscovered: 0,
          latencyMicros: response.queryTimeMs * 1000,
        });
      },
      error: (err) => {
        console.error('Recall failed:', err);
        this.state.isQueryRunning.set(false);
      },
    });

    this.queryText = '';
  }
}
