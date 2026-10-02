import { Routes } from '@angular/router';
import { Workspace } from './workspace';
export const routes: Routes = [
  { path: '', redirectTo: 'home', pathMatch: 'full' },
  ...['home', 'transactions', 'accounts', 'budgets', 'trips', 'insights', 'settings'].map(
    (path) => ({ path, component: Workspace, data: { page: path } }),
  ),
  { path: '**', redirectTo: 'home' },
];
