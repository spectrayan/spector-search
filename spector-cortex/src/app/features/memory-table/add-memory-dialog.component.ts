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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
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
    MatButtonToggleModule,
    MatTabsModule,
    MatTooltipModule,
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

      <!-- Mode Toggle -->
      <mat-button-toggle-group [(ngModel)]="mode" class="mode-toggle" appearance="standard">
        <mat-button-toggle value="text">
          <mat-icon>edit_note</mat-icon>
          Text
        </mat-button-toggle>
        <mat-button-toggle value="ingest">
          <mat-icon>source</mat-icon>
          Ingest
        </mat-button-toggle>
      </mat-button-toggle-group>

      <!-- ═══ TEXT MODE ═══ -->
      @if (mode === 'text') {
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

        <!-- Tags -->
        <mat-form-field appearance="outline" class="field-full">
          <mat-label>Tags</mat-label>
          <input matInput [(ngModel)]="tags" placeholder="tag1, tag2, tag3">
          <mat-hint>Comma-separated synaptic tags (auto-generated if empty)</mat-hint>
        </mat-form-field>
      }

      <!-- ═══ INGEST MODE (File + Directory) ═══ -->
      @if (mode === 'ingest') {
        <!-- Ingest Method Toggle -->
        <mat-button-toggle-group [(ngModel)]="ingestMethod" class="ingest-method-toggle" appearance="standard">
          <mat-button-toggle value="file">
            <mat-icon>upload_file</mat-icon>
            File Upload
          </mat-button-toggle>
          <mat-button-toggle value="directory">
            <mat-icon>folder_open</mat-icon>
            Directory
          </mat-button-toggle>
        </mat-button-toggle-group>

        <!-- ── File Upload Sub-section ── -->
        @if (ingestMethod === 'file') {
          <div class="file-upload-zone"
               [class.drag-over]="isDragOver"
               (dragover)="onDragOver($event)"
               (dragleave)="onDragLeave($event)"
               (drop)="onDrop($event)"
               (click)="fileInput.click()">
            <input #fileInput type="file" hidden
                   (change)="onFileSelected($event)"
                   multiple
                   accept=".txt,.md,.java,.py,.js,.ts,.json,.xml,.yaml,.yml,.csv,.html,.css,.rs,.go,.c,.cpp,.h,.hpp,.kt,.scala,.rb,.sh,.bat,.ps1,.sql,.toml,.ini,.cfg,.log,.properties">
            @if (selectedFiles.length > 0) {
              <mat-icon class="upload-icon uploaded">description</mat-icon>
              <div class="selected-files-list">
                @for (file of selectedFiles; track file.name) {
                  <div class="selected-file-row">
                    <mat-icon class="file-row-icon">insert_drive_file</mat-icon>
                    <span class="file-name">{{ file.name }}</span>
                    <span class="file-size">{{ formatFileSize(file.size) }}</span>
                    <button mat-icon-button class="remove-file-btn" (click)="removeFile($event, file)" matTooltip="Remove">
                      <mat-icon>close</mat-icon>
                    </button>
                  </div>
                }
              </div>
              <button mat-stroked-button class="add-more-btn" (click)="fileInput.click(); $event.stopPropagation()">
                <mat-icon>add</mat-icon> Add more files
              </button>
            } @else {
              <mat-icon class="upload-icon">cloud_upload</mat-icon>
              <span class="upload-label">Drop files here or click to browse</span>
              <span class="upload-hint">Supports text files: .txt, .md, .java, .py, .js, .ts, .json, etc.</span>
              <span class="upload-hint">Select multiple files at once</span>
            }
          </div>
        }

        <!-- ── Directory Sub-section ── -->
        @if (ingestMethod === 'directory') {
          <mat-form-field appearance="outline" class="field-full">
            <mat-label>Directory Path</mat-label>
            <input matInput [(ngModel)]="directoryPath"
                   placeholder="/path/to/directory">
            <mat-icon matSuffix>folder</mat-icon>
            <mat-hint>Absolute path to the directory to ingest</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" class="field-full">
            <mat-label>File Pattern</mat-label>
            <input matInput [(ngModel)]="filePattern"
                   placeholder="**/*.md,**/*.txt,**/*.java">
            <mat-hint>Glob patterns for files to include (comma-separated)</mat-hint>
          </mat-form-field>

          <div class="form-row">
            <mat-form-field appearance="outline" class="field-half">
              <mat-label>Chunk Size</mat-label>
              <input matInput type="number" [(ngModel)]="chunkSize" min="100" max="10000">
              <mat-hint>Characters per chunk</mat-hint>
            </mat-form-field>

            <mat-form-field appearance="outline" class="field-half">
              <mat-label>Chunk Overlap</mat-label>
              <input matInput type="number" [(ngModel)]="chunkOverlap" min="0" max="1000">
              <mat-hint>Overlap between chunks</mat-hint>
            </mat-form-field>
          </div>

          <mat-form-field appearance="outline" class="field-full">
            <mat-label>Skip Directories</mat-label>
            <input matInput [(ngModel)]="skipDirs"
                   placeholder=".git,.idea,.mvn,target,node_modules">
            <mat-hint>Comma-separated directory names to skip</mat-hint>
          </mat-form-field>
        }
      }

      <!-- ═══ SHARED: Tier + Source ═══ -->
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

      <!-- ═══ COGNITIVE HINTS (text mode only) ═══ -->
      @if (mode === 'text') {
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
      }

      <!-- ═══ RESULT FEEDBACK ═══ -->
      @if (ingestResult) {
        <div class="ingest-result" [class.success]="ingestResult.success" [class.error]="!ingestResult.success">
          <mat-icon>{{ ingestResult.success ? 'check_circle' : 'error' }}</mat-icon>
          <span>{{ ingestResult.message }}</span>
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end" class="dialog-actions">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="primary"
              [disabled]="!canSubmit() || submitting"
              (click)="onSubmit()">
        <mat-icon>{{ mode === 'text' ? 'memory' : ingestMethod === 'file' ? 'upload' : 'drive_folder_upload' }}</mat-icon>
        {{ mode === 'text' ? 'Remember' : 'Ingest' }}
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
      min-width: 520px;
      max-height: 70vh;
    }

    .submit-progress {
      margin-bottom: 8px;
      border-radius: 4px;
    }

    .mode-toggle {
      margin-bottom: 12px;
      width: 100%;

      ::ng-deep .mat-button-toggle {
        flex: 1;
      }

      ::ng-deep .mat-button-toggle-label-content {
        display: flex;
        align-items: center;
        gap: 6px;
        justify-content: center;
        font-size: 13px;
      }

      ::ng-deep .mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }
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

    /* ── File Upload Zone ── */
    .file-upload-zone {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 32px 24px;
      border: 2px dashed var(--mat-sys-outline-variant);
      border-radius: 12px;
      background: var(--mat-sys-surface-container);
      cursor: pointer;
      transition: all 0.2s ease;
      position: relative;
      min-height: 120px;

      &:hover {
        border-color: var(--mat-sys-primary);
        background: color-mix(in srgb, var(--mat-sys-primary) 4%, var(--mat-sys-surface-container));
      }

      &.drag-over {
        border-color: var(--mat-sys-primary);
        background: color-mix(in srgb, var(--mat-sys-primary) 8%, var(--mat-sys-surface-container));
        border-style: solid;
      }
    }

    .upload-icon {
      font-size: 40px;
      width: 40px;
      height: 40px;
      color: var(--mat-sys-on-surface-variant);
      opacity: 0.6;

      &.uploaded {
        color: var(--mat-sys-primary);
        opacity: 1;
      }
    }

    .upload-label {
      font-size: 14px;
      font-weight: 500;
      color: var(--mat-sys-on-surface);
    }

    .upload-hint {
      font-size: 12px;
      color: var(--mat-sys-on-surface-variant);
    }

    .file-name {
      font-size: 14px;
      font-weight: 600;
      color: var(--mat-sys-on-surface);
    }

    .file-size {
      font-size: 12px;
      color: var(--mat-sys-on-surface-variant);
    }

    /* ── Ingest Method Sub-Toggle ── */
    .ingest-method-toggle {
      margin-bottom: 8px;
      width: 100%;

      ::ng-deep .mat-button-toggle {
        flex: 1;
      }

      ::ng-deep .mat-button-toggle-label-content {
        display: flex;
        align-items: center;
        gap: 6px;
        justify-content: center;
        font-size: 12px;
      }

      ::ng-deep .mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }
    }

    /* ── Multi-File List ── */
    .selected-files-list {
      width: 100%;
      max-height: 180px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 0 8px;
    }

    .selected-file-row {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 10px;
      border-radius: 8px;
      background: color-mix(in srgb, var(--mat-sys-primary) 6%, var(--mat-sys-surface));
      transition: background 0.15s ease;

      &:hover {
        background: color-mix(in srgb, var(--mat-sys-primary) 12%, var(--mat-sys-surface));
      }
    }

    .file-row-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      color: var(--mat-sys-primary);
    }

    .selected-file-row .file-name {
      flex: 1;
      font-size: 13px;
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .selected-file-row .file-size {
      font-size: 11px;
    }

    .remove-file-btn {
      width: 24px;
      height: 24px;
      line-height: 24px;

      ::ng-deep .mat-icon {
        font-size: 14px;
        width: 14px;
        height: 14px;
      }
    }

    .add-more-btn {
      margin-top: 8px;
      font-size: 12px;

      ::ng-deep .mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        margin-right: 4px;
      }
    }

    /* ── Cognitive Hints ── */
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

    /* ── Ingest Result Feedback ── */
    .ingest-result {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 16px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 500;

      &.success {
        background: color-mix(in srgb, #22c55e 10%, var(--mat-sys-surface-container));
        color: #22c55e;
        border: 1px solid color-mix(in srgb, #22c55e 30%, transparent);
      }

      &.error {
        background: color-mix(in srgb, #ef4444 10%, var(--mat-sys-surface-container));
        color: #ef4444;
        border: 1px solid color-mix(in srgb, #ef4444 30%, transparent);
      }
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

  // ── Mode toggle ──
  mode: 'text' | 'ingest' = 'text';
  ingestMethod: 'file' | 'directory' = 'file';

  // ── Text mode fields ──
  memoryId = '';
  text = '';
  tags = '';
  interest = 50;
  challenge = 30;
  urgency = 20;
  valence = 0;
  arousal = 50;

  // ── File mode fields ──
  selectedFiles: File[] = [];
  isDragOver = false;

  // ── Directory mode fields ──
  directoryPath = '';
  filePattern = '**/*.md,**/*.txt,**/*.java';
  chunkSize = 800;
  chunkOverlap = 100;
  skipDirs = '.git,.idea,.mvn,target,node_modules';

  // ── Shared ──
  tier = 'SEMANTIC';
  source = 'OBSERVED';
  submitting = false;
  ingestResult: { success: boolean; message: string } | null = null;

  readonly tiers = [
    { value: 'WORKING', color: '#f59e0b' },
    { value: 'EPISODIC', color: '#3b82f6' },
    { value: 'SEMANTIC', color: '#8b5cf6' },
    { value: 'PROCEDURAL', color: '#10b981' },
  ];

  readonly sources = ['USER_STATED', 'OBSERVED', 'INFERRED', 'PROCEDURAL'];

  generateId(): void {
    const ts = Date.now().toString(36);
    const rand = Math.random().toString(36).substring(2, 6);
    this.memoryId = `${ts}-${rand}`;
  }

  canSubmit(): boolean {
    if (this.mode === 'text') {
      return !!this.text.trim();
    }
    // ingest mode
    if (this.ingestMethod === 'file') {
      return this.selectedFiles.length > 0;
    }
    return !!this.directoryPath.trim();
  }

  // ── File Upload Helpers ──

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      for (let i = 0; i < files.length; i++) {
        if (!this.selectedFiles.some(f => f.name === files[i].name)) {
          this.selectedFiles.push(files[i]);
        }
      }
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      for (let i = 0; i < input.files.length; i++) {
        if (!this.selectedFiles.some(f => f.name === input.files![i].name)) {
          this.selectedFiles.push(input.files[i]);
        }
      }
    }
    // Reset input so same file can be re-selected
    input.value = '';
  }

  removeFile(event: Event, file: File): void {
    event.stopPropagation();
    this.selectedFiles = this.selectedFiles.filter(f => f !== file);
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  // ── Submit ──

  onSubmit(): void {
    this.ingestResult = null;
    this.submitting = true;

    if (this.mode === 'text') {
      this.submitText();
    } else if (this.ingestMethod === 'file') {
      this.submitFiles();
    } else {
      this.submitDirectory();
    }
  }

  private submitText(): void {
    if (!this.text.trim()) return;

    if (!this.memoryId.trim()) {
      this.generateId();
    }

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
      next: (resp) => {
        this.snackBar.open(
          `Memory "${this.memoryId}" submitted — processing in background`,
          'OK',
          { duration: 3000, panelClass: 'cortex-snackbar' }
        );
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

  private submitFiles(): void {
    if (this.selectedFiles.length === 0) return;

    let completed = 0;
    let failures = 0;
    const total = this.selectedFiles.length;

    for (const file of this.selectedFiles) {
      this.memoryService.ingestFile(file, this.tier, this.source)
        .subscribe({
          next: () => {
            completed++;
            if (completed >= total) {
              this.snackBar.open(
                `${total} file(s) submitted for ingestion — check the bell for progress`,
                'OK',
                { duration: 4000, panelClass: failures > 0 ? 'cortex-snackbar-error' : 'cortex-snackbar' }
              );
              this.dialogRef.close(true);
            }
          },
          error: () => {
            completed++;
            failures++;
            if (completed >= total) {
              this.snackBar.open(
                `${total - failures}/${total} files submitted (${failures} failed)`,
                'Dismiss',
                { duration: 5000, panelClass: 'cortex-snackbar-error' }
              );
              this.submitting = false;
            }
          },
        });
    }
  }

  private submitDirectory(): void {
    if (!this.directoryPath.trim()) return;

    this.memoryService.ingestDirectory({
      path: this.directoryPath,
      filePattern: this.filePattern || undefined,
      chunkSize: this.chunkSize || undefined,
      chunkOverlap: this.chunkOverlap || undefined,
      skipDirs: this.skipDirs || undefined,
    }).subscribe({
      next: (resp) => {
        this.snackBar.open(
          `Directory ingestion submitted — check the bell for progress`,
          'OK',
          { duration: 4000, panelClass: 'cortex-snackbar' }
        );
        this.dialogRef.close(true);
      },
      error: (err) => {
        const msg = err.error || err.message || 'Unknown error';
        this.ingestResult = {
          success: false,
          message: `Directory ingestion failed: ${msg}`,
        };
        this.submitting = false;
      },
    });
  }
}

