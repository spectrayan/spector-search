import { Component, inject, signal } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HeaderComponent } from '../header/header.component';
import { EventStreamService } from '../../core/services/event-stream.service';

interface NavItem {
  icon: string;
  label: string;
  route: string;
}

@Component({
  selector: 'cortex-shell',
  standalone: true,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatTooltipModule,
    HeaderComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  collapsed = signal(false);

  private readonly eventStream = inject(EventStreamService);

  readonly navItems: NavItem[] = [
    { icon: 'dashboard', label: 'Dashboard', route: '/dashboard' },
    { icon: 'search', label: 'Query', route: '/query' },
    { icon: 'psychology', label: 'Memories', route: '/memories' },
    { icon: 'hub', label: 'Graph', route: '/graph' },
    { icon: 'admin_panel_settings', label: 'Admin', route: '/admin' },
  ];

  constructor() {
    this.eventStream.connect();
  }
}
