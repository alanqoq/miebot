import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthSessionStore } from './auth-session.store';

const AUTH_RESOURCE = '/api/auth/';

export const authUnauthorizedInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(AuthSessionStore);
  const router = inject(Router);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        session.markUnauthenticated();
        if (!request.url.startsWith(AUTH_RESOURCE) && !router.url.startsWith('/login')) {
          const returnUrl = router.url.startsWith('/') ? router.url : '/';
          void router.navigate(['/login'], { queryParams: { returnUrl } });
        }
      }
      return throwError(() => error);
    })
  );
};
