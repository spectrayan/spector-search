import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MemoryTableService } from '../../core/services/memory-table.service';

@Component({
  selector: 'cortex-add-memory-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatSliderModule,
    MatChipsModule,
    MatSnackBarModule,
    MatProgressBarModule,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon class="title-icon">add_circle</mat-icon>
      Add Memory
    </h2>

    <mat-dialog-content class="dialog-content">
      @if (submitting) {
        <mat-progress-bar mode="indeterminate" class="submit-progress"></mat-progress-bar>
      }

      <!-- ID -->
      <mat-form-field appearance="outline" class="field-full">
        <mat-label>Memory ID</mat-label>
        <input matInput [(ngModel)]="memoryId" placeholder="Auto-generated if empty">
        <button mat-icon-button matSuffix (click)="generateId()" matTooltip="Generate ID">
          <mat-icon>auto_fix_high</mat-icon>
        </button>
      </mat-form-field>

      <!-- Text -->
      <mat-form-field appearance="outline" class="field-full">
        <mat-label>Memory Text</mat-label>
        <textarea matInput [(ngModel)]="text" rows="4"
                  placeholder="The content of this memory..."
                  required></textarea>
        <mat-hint>{{ text.length }} characters</mat-hint>
      </mat-form-field>

      <!-- Tier + Source row -->
      <div class="form-row">
        <mat-form-field appearance="outline" class="field-half">
          <mat-label>Tier</mat-label>
          <mat-select [(ngModel)]="tier">
            @for (t of tiers; track t) {
              <mat-option [value]="t.value">
                <span class="tier-dot" [style.background]="t.color"></span>
                {{ t.value }}
              </mat-option>
            }
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" class="field-half">
          <mat-label>Source</mat-label>
          <mat-select [(ngModel)]="source">
            @for (s of sources; track s) {
              <mat-option [value]="s">{{ s }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      </div>

      <!-- Tags -->
      <mat-form-field appearance="outline" class="field-full">
        <mat-label>Tags</mat-label>
        <input matInput [(ngModel)]="tags" placeholder="tag1, tag2, tag3">
        <mat-hint>Comma-separated synaptic tags</mat-hint>
      </mat-form-field>

      <!-- ICNU Sliders -->
      <div class="slider-section">
        <h3 class="section-label">
          <mat-icon class="section-icon">tune</mat-icon>
          Cognitive Hints (optional)
        </h3>

        <div class="slider-row">
          <span class="slider-label">Interest</span>
          <mat-slider min="0" max="100" step="1" class="slider">
            <input matSliderThumb [(ngModel)]="interest">
          </mat-slider>
          <span class="slider-value cortex-mono">{{ (interest / 100).toFixed(2) }}</span>
        </div>

        <div class="slider-row">
          <span class="slider-label">Challenge</span>
          <mat-slider min="0" max="100" step="1" class="slider">
            <input matSliderThumb [(ngModel)]="challenge">
          </mat-slider>
          <span class="slider-value cortex-mono">{{ (challenge / 100).toFixed(2) }}</span>
        </div>

        <div class="slider-row">
          <span class="slider-label">Urgency</span>
          <mat-slider min="0" max="100" step="1" class="slider">
            <input matSliderThumb [(ngModel)]="urgency">
          </mat-slider>
          <span class="slider-value cortex-mono">{{ (urgency / 100).toFixed(2) }}</span>
        </div>

        <div class="slider-row">
          <span class="slider-label">Valence</span>
          <mat-slider min="-128" max="127" step="1" class="slider">
            <input matSliderThumb [(ngModel)]="valence">
          </mat-slider>
          <span class="slider-value cortex-mono"
                [class.positive]="valence > 0"
                [class.negative]="valence < 0">
            {{ valence > 0 ? '+' : '' }}{{ valence }}
          </span>
        </div>

        <div class="slider-row">
          <span class="slider-label">Arousal</span>
          <mat-slider min="0" max="255" step="1" class="slider">
            <input matSliderThumb [(ngModel)]="arousal">
          </mat-slider>
          <span class="slider-value cortex-mono">{{ arousal }}</span>
        </div>
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end" class="dialog-actions">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="primary"
              [disabled]="!text.trim() || submitting"
              (click)="onSubmit()">
        <mat-icon>memory</mat-icon>
        Remember
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .dialog-title {
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 18px;
      font-weight: 600;
    }

    .title-icon {
      color: var(--mat-sys-primary);
    }

    .dialog-content {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 480px;
      max-height: 70vh;
    }

    .submit-progress {
      margin-bottom: 8px;
      border-radius: 4px;
    }

    .field-full {
      width: 100%;
    }

    .form-row {
      display: flex;
      gap: 12px;
    }

    .field-half {
      flex: 1;
    }

    .tier-dot {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      margin-right: 8px;
    }

    .slider-section {
      padding: 12px 16px;
      border-radius: 10px;
      background: var(--mat-sys-surface-container);
      border: 1px solid var(--mat-sys-outline-variant);
      margin-top: 4px;
    }

    .section-label {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 12px 0;
      font-size: 13px;
      font-weight: 600;
      color: var(--mat-sys-on-surface-variant);
    }

    .section-icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
    }

    .slider-row {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 4px;
    }

    .slider-label {
      width: 80px;
      font-size: 12px;
      font-weight: 500;
      color: var(--mat-sys-on-surface-variant);
    }

    .slider {
      flex: 1;
    }

    .slider-value {
      width: 48px;
      text-align: right;
      font-size: 12px;
      color: var(--mat-sys-on-surface);

      &.positive { color: #22c55e; }
      &.negative { color: #ef4444; }
    }

    .dialog-actions {
      padding: 12px 24px;
    }
  `],
})
export class AddMemoryDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<AddMemoryDialogComponent>);
  private readonly memoryService = inject(MemoryTableService);
  private readonly snackBar = inject(MatSnackBar);

  memoryId = '';
  text = '';
  tier = 'EPISODIC';
  source = 'USER_STATED';
  tags = '';
  interest = 50;
  challenge = 30;
  urgency = 20;
  valence = 0;
  arousal = 50;
  submitting = false;

  readonly tiers = [
    { value: 'WORKING', color: '#f59e0b' },
    { value: 'EPISODIC', color: '#3b82f6' },
    { value: 'SEMANTIC', color: '#8b5cf6' },
    { value: 'PROCEDURAL', color: '#10b981' },
  ];

  readonly sources = ['USER_STATED', 'OBSERVED', 'INFERRED', 'PROCEDURAL'];

  generateId(): void {
    // Generate a TSID-like ID: timestamp-based + random suffix
    const ts = Date.now().toString(36);
    const rand = Math.random().toString(36).substring(2, 6);
    this.memoryId = `${ts}-${rand}`;
  }

  onSubmit(): void {
    if (!this.text.trim()) return;

    // Auto-generate ID if empty
    if (!this.memoryId.trim()) {
      this.generateId();
    }

    this.submitting = true;

    this.memoryService.remember({
      id: this.memoryId,
      text: this.text,
      tier: this.tier,
      source: this.source,
      tags: this.tags,
      interest: this.interest / 100,
      challenge: this.challenge / 100,
      urgency: this.urgency / 100,
      valence: this.valence,
      arousal: this.arousal,
    }).subscribe({
      next: () => {
        this.snackBar.open(`Memory "${this.memoryId}" stored`, 'OK', {
          duration: 3000,
          panelClass: 'cortex-snackbar',
        });
        this.dialogRef.close(true);
      },
      error: (err) => {
        this.snackBar.open(`Failed: ${err.message}`, 'Dismiss', {
          duration: 5000,
          panelClass: 'cortex-snackbar-error',
        });
        this.submitting = false;
      },
    });
  }
}
