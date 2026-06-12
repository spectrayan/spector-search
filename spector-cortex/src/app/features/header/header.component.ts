import { Component, inject, ViewChild, ElementRef } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatMenuModule } from '@angular/material/menu';
import { MatBadgeModule } from '@angular/material/badge';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { ThemeService } from '../../core/services/theme.service';
import { CortexStateService } from '../../core/services/cortex-state.service';
import { SessionRecorderService, RecordedSession } from '../../core/services/session-recorder.service';
import { SessionReplayService, PLAYBACK_SPEEDS } from '../../core/services/session-replay.service';
import { NotificationService } from '../../core/services/notification.service';
import { PROFILE_PARAMS } from '../../core/models/memory-types';

@Component({
  selector: 'cortex-header',
  imports: [
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
    MatSelectModule,
    MatChipsModule,
    MatTooltipModule,
    MatMenuModule,
    MatBadgeModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent {
  protected readonly theme = inject(ThemeService);
  protected readonly state = inject(CortexStateService);
  protected readonly recorder = inject(SessionRecorderService);
  protected readonly replay = inject(SessionReplayService);
  protected readonly notif = inject(NotificationService);
  protected readonly profileParams = PROFILE_PARAMS;
  protected readonly playbackSpeeds = PLAYBACK_SPEEDS;

  @ViewChild('sessionFileInput')
  private sessionFileInput!: ElementRef<HTMLInputElement>;

  protected toggleRecording(): void {
    if (this.recorder.isRecording()) {
      const session = this.recorder.stopRecording();
      this.recorder.saveToLocalStorage(session);
      this.recorder.exportSession(session);
    } else {
      this.recorder.startRecording();
    }
  }

  protected openImportDialog(): void {
    this.sessionFileInput.nativeElement.click();
  }

  protected async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    const file = input.files[0];
    const session = await this.recorder.importSession(file);
    if (session) {
      this.replay.loadSession(session);
      this.replay.play();
    }
    input.value = ''; // Reset so the same file can be re-imported
  }
}
