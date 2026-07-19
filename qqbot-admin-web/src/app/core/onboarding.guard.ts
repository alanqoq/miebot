import { inject } from '@angular/core';
import { CanActivateChildFn, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { OnboardingApiService } from './onboarding-api.service';

export const onboardingGuard: CanActivateChildFn = (_route, state) => {
  const onboarding = inject(OnboardingApiService);
  const router = inject(Router);

  return onboarding.status().pipe(
    map((status) =>
      status.stage === 'COMPLETE'
        ? true
        : router.createUrlTree(['/setup'], { queryParams: { returnUrl: state.url } })
    ),
    catchError(() => of(router.createUrlTree(['/setup'], { queryParams: { retry: '1' } })))
  );
};

export const onboardingPageGuard: CanActivateFn = () => {
  const onboarding = inject(OnboardingApiService);
  const router = inject(Router);

  return onboarding.status().pipe(
    map((status) => (status.stage === 'COMPLETE' ? router.createUrlTree(['/dashboard']) : true)),
    catchError(() => of(true))
  );
};
