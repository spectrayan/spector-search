import { Route } from '@angular/router';
import { ShellComponent } from './features/shell/shell.component';

export const routes: Route[] = [
  {
    path: '',
    component: ShellComponent,
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent),
      },
      {
        path: 'query',
        loadComponent: () =>
          import('./features/query/query.component').then(m => m.QueryComponent),
      },
      {
        path: 'memories',
        loadComponent: () =>
          import('./features/memory-table/memory-table.component').then(m => m.MemoryTableComponent),
      },
      {
        path: 'memories/:id',
        loadComponent: () =>
          import('./features/memory-detail/memory-detail.component').then(m => m.MemoryDetailComponent),
      },
      {
        path: 'graph',
        loadComponent: () =>
          import('./features/graph-explorer/graph-explorer.component').then(m => m.GraphExplorerComponent),
      },
      {
        path: 'admin',
        loadComponent: () =>
          import('./features/admin/admin.component').then(m => m.AdminComponent),
      },
    ],
  },
  { path: '**', redirectTo: 'dashboard' },
];
