import {
  Component, ElementRef, ViewChild, AfterViewInit, OnDestroy, inject, effect, PLATFORM_ID,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { ThemeService } from '../../core/services/theme.service';
import { PROFILE_PARAMS, CognitiveProfile } from '../../core/models/memory-types';

/** Radar chart axes. */
const AXES = [
  { key: 'alpha', label: 'α Similarity', max: 1.0 },
  { key: 'beta', label: 'β Importance', max: 1.0 },
  { key: 'strictness', label: 'Strictness', max: 10.0 },
  { key: 'hyperfocusBoost', label: 'Hyperfocus', max: 2.0 },
  { key: 'lateralMode', label: 'Lateral', max: 1.0 },
  { key: 'valenceRange', label: 'Valence Range', max: 255 },
];

@Component({
  selector: 'cortex-profile-radar',
  templateUrl: './profile-radar.component.html',
  styleUrl: './profile-radar.component.scss',
})
export class ProfileRadarComponent implements AfterViewInit, OnDestroy {

  @ViewChild('radarCanvas', { static: true })
  private canvasRef!: ElementRef<HTMLCanvasElement>;

  protected readonly state = inject(CortexStateService);
  private readonly themeService = inject(ThemeService);
  private readonly platformId = inject(PLATFORM_ID);

  private ctx!: CanvasRenderingContext2D;
  private animationId = 0;
  private currentValues: number[] = new Array(AXES.length).fill(0);
  private targetValues: number[] = new Array(AXES.length).fill(0);

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) return;

    const canvas = this.canvasRef.nativeElement;
    this.ctx = canvas.getContext('2d')!;
    this.resizeCanvas();
    this.animate();

    const observer = new ResizeObserver(() => this.resizeCanvas());
    observer.observe(canvas.parentElement!);

    // React to profile changes
    effect(() => {
      const profile = this.state.activeProfile();
      this.updateTargets(profile);
    });
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.animationId);
  }

  private resizeCanvas(): void {
    const parent = this.canvasRef.nativeElement.parentElement!;
    const canvas = this.canvasRef.nativeElement;
    const w = parent.clientWidth || 250;
    const h = parent.clientHeight || 250;
    const size = Math.min(w, h, 400);
    if (size < 10) return; // Skip if not yet laid out
    canvas.width = size;
    canvas.height = size;
  }

  private updateTargets(profile: CognitiveProfile): void {
    const params = PROFILE_PARAMS[profile];
    this.targetValues = [
      params.alpha / 1.0,
      params.beta / 1.0,
      params.strictness / 10.0,
      params.hyperfocusBoost / 2.0,
      params.lateralMode ? 1.0 : 0.0,
      (params.valenceMax - params.valenceMin) / 255,
    ];
  }

  private animate(): void {
    this.animationId = requestAnimationFrame(() => this.animate());

    const canvas = this.canvasRef.nativeElement;
    const ctx = this.ctx;
    const w = canvas.width;
    const h = canvas.height;
    const cx = w / 2;
    const cy = h / 2;
    const radius = Math.min(cx, cy) - 40;

    ctx.clearRect(0, 0, w, h);

    // Lerp values
    for (let i = 0; i < AXES.length; i++) {
      this.currentValues[i] += (this.targetValues[i] - this.currentValues[i]) * 0.08;
    }

    const primary = this.themeService.getCssVariable('--mat-sys-primary') || '#bb86fc';
    const tertiary = this.themeService.getCssVariable('--mat-sys-tertiary') || '#03dac6';
    const outline = this.themeService.getCssVariable('--mat-sys-outline-variant') || '#555';
    const onSurface = this.themeService.getCssVariable('--mat-sys-on-surface-variant') || '#aaa';
    const surfaceHigh = this.themeService.getCssVariable('--mat-sys-surface-container-high') || '#333';

    const n = AXES.length;
    const angleStep = (Math.PI * 2) / n;

    // Draw concentric rings
    for (let ring = 1; ring <= 4; ring++) {
      const r = (radius * ring) / 4;
      ctx.beginPath();
      for (let i = 0; i <= n; i++) {
        const angle = i * angleStep - Math.PI / 2;
        const x = cx + r * Math.cos(angle);
        const y = cy + r * Math.sin(angle);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.closePath();
      ctx.strokeStyle = outline;
      ctx.lineWidth = 0.5;
      ctx.stroke();
    }

    // Draw axis lines and labels
    for (let i = 0; i < n; i++) {
      const angle = i * angleStep - Math.PI / 2;
      const x = cx + radius * Math.cos(angle);
      const y = cy + radius * Math.sin(angle);

      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.lineTo(x, y);
      ctx.strokeStyle = outline;
      ctx.lineWidth = 0.5;
      ctx.stroke();

      // Label
      const labelX = cx + (radius + 20) * Math.cos(angle);
      const labelY = cy + (radius + 20) * Math.sin(angle);
      ctx.fillStyle = onSurface;
      ctx.font = '10px Inter, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(AXES[i].label, labelX, labelY);
    }

    // Draw data polygon
    ctx.beginPath();
    for (let i = 0; i <= n; i++) {
      const idx = i % n;
      const angle = idx * angleStep - Math.PI / 2;
      const val = Math.max(0, Math.min(1, this.currentValues[idx]));
      const r = radius * val;
      const x = cx + r * Math.cos(angle);
      const y = cy + r * Math.sin(angle);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.closePath();

    // Fill
    ctx.fillStyle = primary;
    ctx.globalAlpha = 0.15;
    ctx.fill();
    ctx.globalAlpha = 1;

    // Stroke
    ctx.strokeStyle = primary;
    ctx.lineWidth = 2;
    ctx.stroke();

    // Draw data points
    for (let i = 0; i < n; i++) {
      const angle = i * angleStep - Math.PI / 2;
      const val = Math.max(0, Math.min(1, this.currentValues[i]));
      const r = radius * val;
      const x = cx + r * Math.cos(angle);
      const y = cy + r * Math.sin(angle);

      // Glow
      ctx.beginPath();
      ctx.arc(x, y, 6, 0, Math.PI * 2);
      ctx.fillStyle = primary;
      ctx.globalAlpha = 0.3;
      ctx.fill();
      ctx.globalAlpha = 1;

      // Point
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fillStyle = primary;
      ctx.fill();
    }

    // Center label
    const profile = this.state.activeProfile();
    const params = PROFILE_PARAMS[profile];
    ctx.fillStyle = this.themeService.getCssVariable('--mat-sys-on-surface') || '#fff';
    ctx.font = 'bold 13px Inter, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(params.label, cx, cy - 6);

    ctx.fillStyle = onSurface;
    ctx.font = '9px Inter, sans-serif';
    ctx.fillText(params.description.substring(0, 35), cx, cy + 10);
  }
}
