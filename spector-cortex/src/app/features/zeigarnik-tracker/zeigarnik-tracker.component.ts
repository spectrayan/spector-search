import { Component, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CortexStateService } from '../../core/services/cortex-state.service';

@Component({
  selector: 'cortex-zeigarnik-tracker',
  imports: [MatIconModule, MatTooltipModule],
  templateUrl: './zeigarnik-tracker.component.html',
  styleUrl: './zeigarnik-tracker.component.scss',
})
export class ZeigarnikTrackerComponent {
  protected readonly state = inject(CortexStateService);
}
