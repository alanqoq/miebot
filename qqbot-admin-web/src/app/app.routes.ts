import { Routes } from '@angular/router';
import { authGuard, authPageGuard } from './core/auth.guard';
import { onboardingGuard, onboardingPageGuard } from './core/onboarding.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login-page').then((module) => module.LoginPage)
  },
  {
    path: 'setup',
    canActivate: [authPageGuard, onboardingPageGuard],
    loadComponent: () =>
      import('./pages/onboarding/onboarding-page').then((module) => module.OnboardingPage)
  },
  {
    path: '',
    canActivateChild: [authGuard, onboardingGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./pages/dashboard/dashboard-page').then((module) => module.DashboardPage)
      },
      {
        path: 'bots',
        loadComponent: () => import('./pages/bots/bots-page').then((module) => module.BotsPage)
      },
      {
        path: 'plugins',
        loadComponent: () =>
          import('./pages/plugins/plugins-page').then((module) => module.PluginsPage)
      },
      {
        path: 'events',
        loadComponent: () =>
          import('./pages/events/events-page').then((module) => module.EventsPage)
      },
      {
        path: 'system',
        loadComponent: () => import('./pages/system/system-page').then((module) => module.SystemPage)
      },
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: '**', redirectTo: 'dashboard' }
    ]
  }
];
