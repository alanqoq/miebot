import { inject } from '@angular/core';
import { CanActivateChildFn, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthApiService } from './auth-api.service';

function authenticationResult(stateUrl: string) {
  const auth = inject(AuthApiService);
  const router = inject(Router);

  return auth.ensureStatus().pipe(
    map((status) =>
      status.authenticated
        ? true
        : router.createUrlTree(['/login'], { queryParams: { returnUrl: stateUrl } })
    ),
    catchError(() =>
      of(
        router.createUrlTree(['/login'], {
          queryParams: { returnUrl: stateUrl, serviceUnavailable: '1' }
        })
      )
    )
  );
}

export const authGuard: CanActivateChildFn = (_route, state) => authenticationResult(state.url);

export const authPageGuard: CanActivateFn = (_route, state) => authenticationResult(state.url);
