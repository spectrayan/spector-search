import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { QueryInputComponent } from '../query-input/query-input.component';
import { QueryHistoryComponent } from '../query-history/query-history.component';
import { CortexStateService } from '../../core/services/cortex-state.service';

@Component({
  selector: 'cortex-query',
  standalone: true,
  imports: [
    RouterLink,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    QueryInputComponent,
    QueryHistoryComponent,
  ],
  templateUrl: './query.component.html',
  styleUrl: './query.component.scss',
})
export class QueryComponent {
  protected readonly state = inject(CortexStateService);

  protected tierColor(tier: string): string {
    const COLORS: Record<string, string> = {
      EPISODIC: '#a78bfa',
      SEMANTIC: '#34d399',
      WORKING: '#fbbf24',
      PROCEDURAL: '#60a5fa',
    };
    return COLORS[tier] ?? '#9e9e9e';
  }

  protected formatScore(score: number): string {
    return score < 0.01 ? score.toExponential(2) : score.toFixed(4);
  }
}
