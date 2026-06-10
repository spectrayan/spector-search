import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MemoryTableService, MemoryRow } from '../../core/services/memory-table.service';
import { AddMemoryDialogComponent } from './add-memory-dialog.component';

@Component({
  selector: 'cortex-memory-table',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatSelectModule,
    MatFormFieldModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatMenuModule,
    MatPaginatorModule,
    MatSnackBarModule,
    MatDialogModule,
  ],
  templateUrl: './memory-table.component.html',
  styleUrl: './memory-table.component.scss',
})
export class MemoryTableComponent implements OnInit {
  protected readonly table = inject(MemoryTableService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly tiers = ['WORKING', 'EPISODIC', 'SEMANTIC', 'PROCEDURAL'];

  readonly tierColors: Record<string, string> = {
    WORKING: '#f59e0b',
    EPISODIC: '#3b82f6',
    SEMANTIC: '#8b5cf6',
    PROCEDURAL: '#10b981',
  };

  readonly sortableColumns = [
    { key: 'timestampMs', label: 'Time' },
    { key: 'importance', label: 'Importance' },
    { key: 'valence', label: 'Valence' },
    { key: 'agentRecallCount', label: 'Recalls' },
  ];

  ngOnInit(): void {
    this.table.loadPage();
  }

  /** Handle mat-paginator page events. */
  onPageChange(event: PageEvent): void {
    this.table.page.set(event.pageIndex);
    this.table.pageSize.set(event.pageSize);
    this.table.loadPage();
  }

  /** Format epoch ms to relative time string */
  relativeTime(ms: number): string {
    if (!ms) return '—';
    const diff = Date.now() - ms;
    if (diff < 60_000) return `${Math.floor(diff / 1000)}s ago`;
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return `${Math.floor(diff / 86_400_000)}d ago`;
  }

  /** Copy ID to clipboard */
  copyId(id: string, event: MouseEvent): void {
    event.stopPropagation();
    navigator.clipboard.writeText(id);
  }

  /** Track by for rows */
  trackRow(_: number, row: MemoryRow): string {
    return row.id;
  }

  /** Format tombstone ratio as percentage */
  formatRatio(ratio: number): string {
    return (ratio * 100).toFixed(1) + '%';
  }

  /** Navigate to memory detail view */
  onRowClick(row: MemoryRow): void {
    this.router.navigate(['/memories', row.id]);
  }

  /** Open add memory dialog */
  openAddDialog(): void {
    const dialogRef = this.dialog.open(AddMemoryDialogComponent, {
      width: '560px',
      maxWidth: '95vw',
      panelClass: 'cortex-dialog',
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.table.loadPage();
      }
    });
  }

  /** Reinforce a memory */
  onReinforce(row: MemoryRow, event: MouseEvent): void {
    event.stopPropagation();
    this.table.reinforce(row.id, 1).subscribe({
      next: () => {
        this.snackBar.open(`Reinforced "${row.id}"`, 'OK', { duration: 2000 });
        this.table.loadPage();
      },
      error: () => this.snackBar.open('Reinforce failed', 'Dismiss', { duration: 3000 }),
    });
  }

  /** Suppress a memory */
  onSuppress(row: MemoryRow, event: MouseEvent): void {
    event.stopPropagation();
    this.table.suppress(row.id, 'Manual suppression via Cortex UI').subscribe({
      next: () => {
        this.snackBar.open(`Suppressed "${row.id}"`, 'OK', { duration: 2000 });
        this.table.loadPage();
      },
      error: () => this.snackBar.open('Suppress failed', 'Dismiss', { duration: 3000 }),
    });
  }

  /** Toggle resolved status */
  onResolve(row: MemoryRow, event: MouseEvent): void {
    event.stopPropagation();
    const obs = row.resolved ? this.table.unresolve(row.id) : this.table.resolve(row.id);
    obs.subscribe({
      next: () => {
        const action = row.resolved ? 'Unresolved' : 'Resolved';
        this.snackBar.open(`${action} "${row.id}"`, 'OK', { duration: 2000 });
        this.table.loadPage();
      },
      error: () => this.snackBar.open('Resolve toggle failed', 'Dismiss', { duration: 3000 }),
    });
  }

  /** Forget (tombstone) a memory */
  onForget(row: MemoryRow, event: MouseEvent): void {
    event.stopPropagation();
    this.table.forget(row.id).subscribe({
      next: () => {
        this.snackBar.open(`Forgotten "${row.id}"`, 'OK', { duration: 2000 });
        this.table.loadPage();
      },
      error: () => this.snackBar.open('Forget failed', 'Dismiss', { duration: 3000 }),
    });
  }

  /** Trigger reflect consolidation */
  onReflect(): void {
    this.table.reflect().subscribe({
      next: () => {
        this.snackBar.open('Reflect cycle completed', 'OK', { duration: 3000 });
        this.table.loadPage();
      },
      error: () => this.snackBar.open('Reflect failed', 'Dismiss', { duration: 3000 }),
    });
  }
}
