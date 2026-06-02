import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CortexStateService, MemoryDiffPair } from '../../core/services/cortex-state.service';
import { ThemeService } from '../../core/services/theme.service';

interface DiffMetric {
  label: string;
  preValue: number;
  postValue: number;
  delta: number;
  deltaPercent: number;
  unit: string;
}

@Component({
  selector: 'cortex-memory-diff',
  templateUrl: './memory-diff.component.html',
  styleUrl: './memory-diff.component.scss',
})
export class MemoryDiffComponent implements AfterViewInit, OnDestroy {

  @ViewChild('diffCanvas', { static: true })
  private canvasRef!: ElementRef<HTMLCanvasElement>;

  private readonly state = inject(CortexStateService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  private ctx!: CanvasRenderingContext2D;
  private animationId = 0;

  constructor() {
    effect(() => {
      this.state.memoryDiffs();
    });
  }

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;
    this.ctx = this.canvasRef.nativeElement.getContext('2d')!;
    const observer = new ResizeObserver(() => this.resizeCanvas());
    observer.observe(this.canvasRef.nativeElement.parentElement!);
    this.resizeCanvas();
    this.draw();
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.animationId);
  }

  private resizeCanvas(): void {
    const parent = this.canvasRef.nativeElement.parentElement!;
    this.canvasRef.nativeElement.width = parent.clientWidth;
    this.canvasRef.nativeElement.height = parent.clientHeight;
  }

  private draw(): void {
    this.animationId = requestAnimationFrame(() => this.draw());

    const canvas = this.canvasRef.nativeElement;
    const ctx = this.ctx;
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const diffs = this.state.memoryDiffs();
    const activeIdx = this.state.activeDiffIndex();
    if (diffs.length === 0) {
      this.drawEmptyState(ctx, w, h);
      return;
    }

    const diff = diffs[Math.min(activeIdx, diffs.length - 1)];
    const metrics = this.computeMetrics(diff);

    const primary = this.themeService.getCanvasColor('--mat-sys-primary', '#bb86fc');
    const tertiary = this.themeService.getCanvasColor('--mat-sys-tertiary', '#03dac6');
    const error = this.themeService.getCanvasColor('--mat-sys-error', '#f44336');
    const onSurface = this.themeService.getCanvasColor('--mat-sys-on-surface-variant', '#aaa');
    const outline = this.themeService.getCanvasColor('--mat-sys-outline-variant', '#555');

    const padding = { top: 8, right: 16, bottom: 8, left: 16 };
    const chartW = w - padding.left - padding.right;
    const barHeight = Math.min(18, (h - padding.top - padding.bottom) / metrics.length - 6);
    const gap = 4;

    // Find max value for scaling
    const maxVal = Math.max(...metrics.map(m => Math.max(m.preValue, m.postValue)), 1);

    for (let i = 0; i < metrics.length; i++) {
      const m = metrics[i];
      const y = padding.top + i * (barHeight * 2 + gap * 2 + 8);

      // Label
      ctx.fillStyle = onSurface;
      ctx.font = '10px Inter, sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText(m.label, padding.left, y + 4);

      // Delta badge
      const deltaColor = m.delta < 0 ? tertiary : m.delta > 0 ? error : onSurface;
      const deltaText = `${m.delta > 0 ? '+' : ''}${this.formatNumber(m.delta)} (${m.delta > 0 ? '+' : ''}${m.deltaPercent.toFixed(1)}%)`;
      ctx.fillStyle = deltaColor;
      ctx.font = '9px "JetBrains Mono", monospace';
      ctx.textAlign = 'right';
      ctx.fillText(deltaText, w - padding.right, y + 4);

      // Pre bar (dimmer)
      const preWidth = (m.preValue / maxVal) * (chartW * 0.7);
      ctx.fillStyle = primary;
      ctx.globalAlpha = 0.3;
      this.roundRect(ctx, padding.left, y + 8, preWidth, barHeight, 3);
      ctx.fill();

      // Post bar (brighter, overlaid)
      const postWidth = (m.postValue / maxVal) * (chartW * 0.7);
      ctx.globalAlpha = 0.8;
      this.roundRect(ctx, padding.left, y + 8 + barHeight + 2, postWidth, barHeight, 3);
      ctx.fill();
      ctx.globalAlpha = 1;

      // Values
      ctx.fillStyle = onSurface;
      ctx.font = '9px "JetBrains Mono", monospace';
      ctx.textAlign = 'left';
      ctx.globalAlpha = 0.6;
      ctx.fillText(`Pre:  ${this.formatNumber(m.preValue)}${m.unit}`, padding.left + Math.max(preWidth, postWidth) + 8, y + 8 + barHeight - 2);
      ctx.globalAlpha = 1;
      ctx.fillText(`Post: ${this.formatNumber(m.postValue)}${m.unit}`, padding.left + Math.max(preWidth, postWidth) + 8, y + 8 + barHeight * 2);
    }

    // Cycle indicator at bottom
    if (diffs.length > 1) {
      ctx.fillStyle = onSurface;
      ctx.font = '9px Inter, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(`Cycle ${activeIdx + 1}/${diffs.length}`, w / 2, h - 4);
    }
  }

  private drawEmptyState(ctx: CanvasRenderingContext2D, w: number, h: number): void {
    const onSurface = this.themeService.getCanvasColor('--mat-sys-on-surface-variant', '#aaa');
    ctx.fillStyle = onSurface;
    ctx.font = '12px Inter, sans-serif';
    ctx.textAlign = 'center';
    ctx.globalAlpha = 0.5;
    ctx.fillText('Waiting for reflect() cycle...', w / 2, h / 2);
    ctx.globalAlpha = 1;
  }

  private computeMetrics(diff: MemoryDiffPair): DiffMetric[] {
    const pre = diff.pre;
    const post = diff.post;

    const metric = (label: string, preVal: number, postVal: number, unit = ''): DiffMetric => ({
      label,
      preValue: preVal,
      postValue: postVal,
      delta: postVal - preVal,
      deltaPercent: preVal > 0 ? ((postVal - preVal) / preVal) * 100 : 0,
      unit,
    });

    return [
      metric('Hebbian Edges', pre.hebbianEdgeCount, post.hebbianEdgeCount),
      metric('Temporal Links', pre.temporalLinkCount, post.temporalLinkCount),
      metric('Entity Nodes', pre.entityNodeCount, post.entityNodeCount),
      metric('Entity Edges', pre.entityEdgeCount, post.entityEdgeCount),
      metric('Off-Heap', pre.offHeapBytes, post.offHeapBytes, ' B'),
      metric('Tombstones', pre.tombstoneCount, post.tombstoneCount),
    ];
  }

  private formatNumber(n: number): string {
    if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (Math.abs(n) >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return n.toFixed(0);
  }

  private roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }
}
