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
   */
  getCssVariable(name: string): string {
    if (!this.isBrowser) return '';
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }
}
