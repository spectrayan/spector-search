// ═══════════════════════════════════════════════════════════════════════
// Spector Cortex — Theme Service
// ═══════════════════════════════════════════════════════════════════════
// Manages M3 dark/light theme toggle with localStorage persistence.

import { Injectable, signal, computed, effect, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

const THEME_KEY = 'cortex-theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {

  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** Current dark mode state. */
  readonly isDark = signal(true);

  /** Theme label for display. */
  readonly themeLabel = computed(() => this.isDark() ? 'Dark' : 'Light');

  /** Theme icon for toggle button. */
  readonly themeIcon = computed(() => this.isDark() ? 'dark_mode' : 'light_mode');

  constructor() {
    if (this.isBrowser) {
      const saved = localStorage.getItem(THEME_KEY);
      if (saved !== null) {
        this.isDark.set(saved === 'dark');
      }
    }

    // Sync theme to DOM whenever signal changes
    effect(() => {
      if (this.isBrowser) {
        const theme = this.isDark() ? 'dark' : 'light';
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
      }
    });
  }

  /** Toggle between dark and light themes. */
  toggle(): void {
    this.isDark.update(v => !v);
  }

  /**
   * Reads a computed CSS variable value from the document root.
   * Useful for passing M3 colors into Canvas/WebGL contexts.
   *
   * **Warning:** Angular Material 21 may return oklch() colors, which
   * Canvas 2D does NOT support. For canvas contexts, use
   * {@link getCanvasColor} instead.
   */
  getCssVariable(name: string): string {
    if (!this.isBrowser) return '';
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  /**
   * Resolves a CSS variable to a hex color that Canvas 2D can use.
   * Works with oklch(), rgb(), hsl(), or any CSS color format —
   * the browser resolves it to rgb() via a hidden element.
   */
  private colorCache = new Map<string, string>();
  private probeEl: HTMLDivElement | null = null;

  getCanvasColor(name: string, fallback = '#bb86fc'): string {
    if (!this.isBrowser) return fallback;

    // Check cache (invalidated on theme toggle via key prefix)
    const cacheKey = `${this.isDark() ? 'd' : 'l'}:${name}`;
    const cached = this.colorCache.get(cacheKey);
    if (cached) return cached;

    // Create probe element once
    if (!this.probeEl) {
      this.probeEl = document.createElement('div');
      this.probeEl.style.display = 'none';
      document.body.appendChild(this.probeEl);
    }

    // Apply the CSS variable as background-color, then read the resolved RGB
    this.probeEl.style.backgroundColor = `var(${name})`;
    const computed = getComputedStyle(this.probeEl).backgroundColor;

    // Convert "rgb(r, g, b)" or "rgba(r, g, b, a)" to hex
    const match = computed.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    if (!match) {
      this.colorCache.set(cacheKey, fallback);
      return fallback;
    }

    const hex = '#' + [match[1], match[2], match[3]]
      .map(n => parseInt(n, 10).toString(16).padStart(2, '0'))
      .join('');

    this.colorCache.set(cacheKey, hex);
    return hex;
  }
}
