import { Component, inject } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CortexStateService } from '../../core/services/cortex-state.service';

@Component({
  selector: 'cortex-habituation-meter',
  imports: [DecimalPipe, MatIconModule, MatTooltipModule],
  templateUrl: './habituation-meter.component.html',
  styleUrl: './habituation-meter.component.scss',
})
export class HabituationMeterComponent {
  protected readonly state = inject(CortexStateService);
}
