import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree
} from '@angular/router';
import { firstValueFrom, Observable, of, throwError } from 'rxjs';
import { OnboardingApiService, OnboardingStatus } from './onboarding-api.service';
import { onboardingGuard, onboardingPageGuard } from './onboarding.guard';

describe('onboarding guards', () => {
  const status = vi.fn();

  beforeEach(() => {
    status.mockReset();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: OnboardingApiService, useValue: { status } }]
    });
  });

  it('allows regular administration routes after setup is complete', async () => {
    status.mockReturnValue(of(onboardingStatus('COMPLETE')));

    expect(await runChildGuard('/bots')).toBe(true);
  });

  it('redirects regular routes to the setup page while setup is incomplete', async () => {
    status.mockReturnValue(of(onboardingStatus('DATABASE')));

    const result = await runChildGuard('/system');

    expect(serialize(result)).toBe('/setup?returnUrl=%2Fsystem');
  });

  it('keeps setup reachable when status loading fails', async () => {
    status.mockReturnValue(throwError(() => new Error('offline')));

    const result = await runChildGuard('/dashboard');

    expect(serialize(result)).toBe('/setup?retry=1');
  });

  it('redirects the setup page after setup is complete', async () => {
    status.mockReturnValue(of(onboardingStatus('COMPLETE')));

    const result = await runPageGuard('/setup');

    expect(serialize(result)).toBe('/');
  });

  it('allows the setup page while a stage remains', async () => {
    status.mockReturnValue(of(onboardingStatus('BOT')));

    expect(await runPageGuard('/setup')).toBe(true);
  });

  function serialize(result: boolean | UrlTree): string {
    expect(result).toBeInstanceOf(UrlTree);
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }
});

function onboardingStatus(stage: OnboardingStatus['stage']): OnboardingStatus {
  return {
    stage,
    databaseType: stage === 'BOT' || stage === 'COMPLETE' ? 'SQLITE' : null,
    botCount: stage === 'COMPLETE' ? 1 : 0,
    completedAt: stage === 'COMPLETE' ? '2026-07-17T12:00:00Z' : null
  };
}

function runChildGuard(url: string): Promise<boolean | UrlTree> {
  const result = TestBed.runInInjectionContext(() =>
    onboardingGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)
  );
  return firstValueFrom(result as Observable<boolean | UrlTree>);
}

function runPageGuard(url: string): Promise<boolean | UrlTree> {
  const result = TestBed.runInInjectionContext(() =>
    onboardingPageGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)
  );
  return firstValueFrom(result as Observable<boolean | UrlTree>);
}
