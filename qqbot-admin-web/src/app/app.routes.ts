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
        path: 'modules/:moduleId/:contributionId',
        loadComponent: () =>
          import('./pages/module/module-page').then((module) => module.ModulePage)
      },
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () =>
          import('./pages/module/module-landing-page').then((module) => module.ModuleLandingPage)
      },
      { path: '**', redirectTo: '' }
    ]
  }
];
