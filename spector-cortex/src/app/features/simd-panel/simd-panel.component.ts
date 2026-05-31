import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { ThemeService } from '../../core/services/theme.service';

@Component({
  selector: 'cortex-simd-panel',
  templateUrl: './simd-panel.component.html',
  styleUrl: './simd-panel.component.scss',
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

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    const canvas = this.canvasRef.nativeElement;
    this.ctx = canvas.getContext('2d')!;

    this.resizeCanvas();
    this.animate();

    const observer = new ResizeObserver(() => this.resizeCanvas());
    observer.observe(canvas.parentElement!);

    // React to SIMD events
    effect(() => {
      const simd = this.state.simdState();
      if (simd) {
        this.updateLanes(simd.laneCount, simd.tailLanesActive, simd.totalIterations);
      }
    });
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

  private updateLanes(laneCount: number, tailActive: number, iterations: number): void {
    // Main loop: all lanes full
    for (let i = 0; i < laneCount; i++) {
      this.targetIntensities[i] = 1.0;
    }
    // Tail: partial
    for (let i = tailActive; i < laneCount; i++) {
      this.targetIntensities[i] = 0.15;
    }
    // Clear unused lanes
    for (let i = laneCount; i < 16; i++) {
      this.targetIntensities[i] = 0;
    }
  }

  private animate(): void {
    this.animationId = requestAnimationFrame(() => this.animate());

    const canvas = this.canvasRef.nativeElement;
    const ctx = this.ctx;
    const w = canvas.width;
    const h = canvas.height;

    ctx.clearRect(0, 0, w, h);

    // Get M3 colors
    const primary = this.themeService.getCssVariable('--mat-sys-primary') || '#bb86fc';
    const surface = this.themeService.getCssVariable('--mat-sys-surface-container-highest') || '#333';
    const outline = this.themeService.getCssVariable('--mat-sys-outline-variant') || '#555';
    const tertiary = this.themeService.getCssVariable('--mat-sys-tertiary') || '#03dac6';

    const laneCount = this.state.simdState()?.laneCount ?? 16;
    const laneWidth = Math.floor((w - 32) / laneCount) - 4;
    const laneHeight = h - 40;
    const startX = (w - (laneWidth + 4) * laneCount) / 2;

    for (let i = 0; i < laneCount; i++) {
      // Lerp intensity
      this.laneIntensities[i] += (this.targetIntensities[i] - this.laneIntensities[i]) * 0.15;
      const intensity = this.laneIntensities[i];

      const x = startX + i * (laneWidth + 4);
      const y = 20;

      // Background
      ctx.fillStyle = surface;
      ctx.beginPath();
      ctx.roundRect(x, y, laneWidth, laneHeight, 4);
      ctx.fill();

      // Active fill
      if (intensity > 0) {
        const fillHeight = laneHeight * intensity;
        const fillY = y + laneHeight - fillHeight;

        // Gradient
        const gradient = ctx.createLinearGradient(x, fillY, x, y + laneHeight);
        gradient.addColorStop(0, primary);
        gradient.addColorStop(1, tertiary);

        ctx.fillStyle = gradient;
        ctx.globalAlpha = 0.3 + intensity * 0.7;
        ctx.beginPath();
        ctx.roundRect(x, fillY, laneWidth, fillHeight, [0, 0, 4, 4]);
        ctx.fill();

        // Glow
        if (intensity > 0.5) {
          ctx.shadowColor = primary;
          ctx.shadowBlur = 8 * intensity;
          ctx.fillStyle = primary;
          ctx.globalAlpha = intensity * 0.2;
          ctx.beginPath();
          ctx.roundRect(x, fillY, laneWidth, fillHeight, [0, 0, 4, 4]);
          ctx.fill();
          ctx.shadowBlur = 0;
        }

        ctx.globalAlpha = 1;
      }

      // Border
      ctx.strokeStyle = outline;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(x, y, laneWidth, laneHeight, 4);
      ctx.stroke();

      // Lane label
      ctx.fillStyle = this.themeService.getCssVariable('--mat-sys-on-surface-variant') || '#aaa';
      ctx.font = '9px "JetBrains Mono", monospace';
      ctx.textAlign = 'center';
      ctx.fillText(`L${i}`, x + laneWidth / 2, y + laneHeight + 14);
    }

    // Slowly decay targets
    for (let i = 0; i < 16; i++) {
      this.targetIntensities[i] *= 0.992;
    }
  }
}
