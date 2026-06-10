import { Component, inject, signal, OnInit } from '@angular/core';
import { KeyValuePipe, JsonPipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FormsModule } from '@angular/forms';
import { MemoryTableService } from '../../core/services/memory-table.service';

@Component({
  selector: 'cortex-admin',
  standalone: true,
  imports: [
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatProgressBarModule,
    MatChipsModule,
    MatDividerModule,
    MatSelectModule,
    MatTooltipModule,
    FormsModule,
    KeyValuePipe,
    JsonPipe,
  ],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss',
})
export class AdminComponent implements OnInit {
  private readonly memoryService = inject(MemoryTableService);

  readonly status = signal<any>(null);
  readonly vacuumResult = signal<any>(null);
  readonly reflectResult = signal<any>(null);
  readonly loading = signal(false);
  readonly vacuumTier = signal('SEMANTIC');
  readonly vacuumLoading = signal(false);
  readonly reflectLoading = signal(false);

  ngOnInit(): void {
    this.loadStatus();
  }

  loadStatus(): void {
    this.loading.set(true);
    this.memoryService.getStatus().subscribe({
      next: (s) => {
        this.status.set(s);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  triggerVacuum(): void {
    this.vacuumLoading.set(true);
    this.memoryService.vacuum(this.vacuumTier()).subscribe({
      next: (result) => {
        this.vacuumResult.set(result);
        this.vacuumLoading.set(false);
        this.loadStatus();
      },
      error: () => this.vacuumLoading.set(false),
    });
  }

  triggerReflect(): void {
    this.reflectLoading.set(true);
    this.memoryService.reflect().subscribe({
      next: (result) => {
        this.reflectResult.set(result);
        this.reflectLoading.set(false);
        this.loadStatus();
      },
      error: () => this.reflectLoading.set(false),
    });
  }
}
