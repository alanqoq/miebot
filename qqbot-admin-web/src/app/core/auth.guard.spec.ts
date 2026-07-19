import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, Observable, of, throwError } from 'rxjs';
import { AuthApiService } from './auth-api.service';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  const ensureStatus = vi.fn();

  beforeEach(() => {
    ensureStatus.mockReset();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthApiService, useValue: { ensureStatus } }]
    });
  });

  it('allows authenticated routes', async () => {
    ensureStatus.mockReturnValue(
      of({ setupRequired: false, authenticated: true, username: 'admin' })
    );

    const result = await runGuard('/bots');

    expect(result).toBe(true);
  });

  it('redirects anonymous users to login with the original route', async () => {
    ensureStatus.mockReturnValue(
      of({ setupRequired: false, authenticated: false, username: null })
    );

    const result = await runGuard('/bots');
    const router = TestBed.inject(Router);

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fbots');
  });

  it('redirects to the login recovery state when the service is unavailable', async () => {
    ensureStatus.mockReturnValue(throwError(() => new Error('offline')));

    const result = await runGuard('/system');
    const router = TestBed.inject(Router);

    expect(router.serializeUrl(result as UrlTree)).toBe(
      '/login?returnUrl=%2Fsystem&serviceUnavailable=1'
    );
  });
});

function runGuard(url: string): Promise<boolean | UrlTree> {
  const result = TestBed.runInInjectionContext(() =>
    authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)
  );
  return firstValueFrom(result as Observable<boolean | UrlTree>);
}
