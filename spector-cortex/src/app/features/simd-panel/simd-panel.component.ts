import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { DecimalPipe, isPlatformBrowser } from '@angular/common';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { ThemeService } from '../../core/services/theme.service';

@Component({
  selector: 'cortex-simd-panel',
  templateUrl: './simd-panel.component.html',
  styleUrl: './simd-panel.component.scss',
  imports: [DecimalPipe],
})
export class SimdPanelComponent implements AfterViewInit, OnDestroy {

  @ViewChild('simdCanvas', { static: true })
  private canvasRef!: ElementRef<HTMLCanvasElement>;

  protected readonly state = inject(CortexStateService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  private ctx!: CanvasRenderingContext2D;
  private animationId = 0;
  private laneIntensities: number[] = new Array(16).fill(0);
  private targetIntensities: number[] = new Array(16).fill(0);
  private laneUtilization: number[] = new Array(16).fill(0);    // per-lane vector count ratio
  private targetUtilization: number[] = new Array(16).fill(0);

  constructor() {
    // React to SIMD events — must be in constructor for injection context
    effect(() => {
      const simd = this.state.simdState();
      if (simd) {
        this.updateLanes(simd.laneWidth, simd.vectorsProcessed, simd.durationMicros);
      }
    });
  }

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    const canvas = this.canvasRef.nativeElement;
    this.ctx = canvas.getContext('2d')!;

    this.resizeCanvas();
    this.animate();

    const observer = new ResizeObserver(() => this.resizeCanvas());
    observer.observe(canvas.parentElement!);
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.animationId);
  }

  private resizeCanvas(): void {
    const parent = this.canvasRef.nativeElement.parentElement!;
    const canvas = this.canvasRef.nativeElement;
    canvas.width = parent.clientWidth;
    canvas.height = Math.min(parent.clientHeight, 160);
  }

  private updateLanes(laneWidth: number, vectorsProcessed: number, durationMicros: number): void {
    // Speed intensity: linear ramp 0→1 over 0→5000µs (inverted: faster = higher)
    // Saturates at 1.0 for ≤200µs, minimum 0.15 for very slow queries
    const speedIntensity = Math.max(0.15, Math.min(1.0, 1.0 - (durationMicros / 5000)));

    // Utilization: how many vectors each lane processed (log scale for visibility)
    // 500 vectors / 16 lanes = 31 per lane → log2(31+1)/log2(256) ≈ 0.63
    const vectorsPerLane = vectorsProcessed / Math.max(1, laneWidth);
    const baseUtil = Math.min(1.0, Math.log2(vectorsPerLane + 1) / 8);

    for (let i = 0; i < laneWidth; i++) {
      // Per-lane jitter: ±15% variation so lanes don't all look identical
      const jitter = 0.85 + Math.random() * 0.30;
      this.targetUtilization[i] = Math.min(1.0, baseUtil * jitter);
      this.targetIntensities[i] = Math.min(1.0, speedIntensity * (0.9 + Math.random() * 0.2));
    }
    // Clear unused lanes
    for (let i = laneWidth; i < 16; i++) {
      this.targetIntensities[i] = 0;
      this.targetUtilization[i] = 0;
    }
  }

  private frameCount = 0;

  private animate(): void {
    this.animationId = requestAnimationFrame(() => this.animate());
    this.frameCount++;

    const canvas = this.canvasRef.nativeElement;
    const ctx = this.ctx;
    const w = canvas.width;
    const h = canvas.height;

    if (w === 0 || h === 0) return;
    ctx.clearRect(0, 0, w, h);

    // Use hardcoded hex colors to guarantee canvas compatibility
    const primaryHex = '#7c4dff';     // deep purple
    const secondaryHex = '#ff5722';   // deep orange
    const tertiaryHex = '#00bfa5';    // teal
    const surfaceHex = '#1e1e2e';     // dark surface
    const outlineHex = '#444';        // subtle border
    const labelHex = '#999';          // label text

    const laneCount = this.state.simdState()?.laneWidth ?? 16;
    const laneW = Math.floor((w - 32) / laneCount) - 4;
    const laneHeight = h - 40;
    const startX = (w - (laneW + 4) * laneCount) / 2;

    for (let i = 0; i < laneCount; i++) {
      // Lerp intensity and utilization toward targets
      this.laneIntensities[i] += (this.targetIntensities[i] - this.laneIntensities[i]) * 0.15;
      this.laneUtilization[i] += (this.targetUtilization[i] - this.laneUtilization[i]) * 0.12;

      // Guarantee minimum visible fill (20%) so user always sees something
      const intensity = Math.max(0.2, this.laneIntensities[i]);
      const util = Math.max(0.15, this.laneUtilization[i]);

      const x = startX + i * (laneW + 4);
      const y = 20;

      // ── Background ──
      ctx.fillStyle = surfaceHex;
      ctx.beginPath();
      ctx.roundRect(x, y, laneW, laneHeight, 4);
      ctx.fill();

      // ── Utilization fill (how full/how many vectors) ── orange/warm
      const utilHeight = laneHeight * util;
      const utilY = y + laneHeight - utilHeight;
      ctx.fillStyle = secondaryHex;
      ctx.globalAlpha = 0.3 + util * 0.5;
      ctx.beginPath();
      ctx.roundRect(x, utilY, laneW, utilHeight, [0, 0, 4, 4]);
      ctx.fill();
      ctx.globalAlpha = 1;

      // ── Intensity fill (throughput speed) ── purple/cool, overlaid
      const fillHeight = laneHeight * intensity;
      const fillY = y + laneHeight - fillHeight;
      ctx.fillStyle = primaryHex;
      ctx.globalAlpha = 0.25 + intensity * 0.55;
      ctx.beginPath();
      ctx.roundRect(x, fillY, laneW, fillHeight, [0, 0, 4, 4]);
      ctx.fill();

      // Glow for hot lanes
      if (intensity > 0.5) {
        ctx.shadowColor = primaryHex;
        ctx.shadowBlur = 10 * intensity;
        ctx.fillStyle = primaryHex;
        ctx.globalAlpha = intensity * 0.15;
        ctx.beginPath();
        ctx.roundRect(x, fillY, laneW, fillHeight, [0, 0, 4, 4]);
        ctx.fill();
        ctx.shadowBlur = 0;
      }

      ctx.globalAlpha = 1;

      // ── Border ──
      ctx.strokeStyle = outlineHex;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(x, y, laneW, laneHeight, 4);
      ctx.stroke();

      // ── Lane label ──
      ctx.fillStyle = labelHex;
      ctx.font = '9px "JetBrains Mono", monospace';
      ctx.textAlign = 'center';
      ctx.fillText(`L${i}`, x + laneW / 2, y + laneHeight + 14);
    }

    // Slowly decay targets
    for (let i = 0; i < 16; i++) {
      this.targetIntensities[i] *= 0.992;
      this.targetUtilization[i] *= 0.996;
    }
  }
}
