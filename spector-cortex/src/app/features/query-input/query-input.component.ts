import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { CortexStateService } from '../../core/services/cortex-state.service';

@Component({
  selector: 'cortex-query-input',
  imports: [FormsModule, MatIconModule],
  templateUrl: './query-input.component.html',
  styleUrl: './query-input.component.scss',
})
export class QueryInputComponent {

  protected readonly state = inject(CortexStateService);
  protected queryText = '';

  protected submitQuery(): void {
    if (!this.queryText.trim()) return;
    this.state.isQueryRunning.set(true);

    // Simulate query execution with mock trace
    setTimeout(() => {
      this.state.isQueryRunning.set(false);
    }, 800 + Math.random() * 1500);

    this.queryText = '';
  }
}
