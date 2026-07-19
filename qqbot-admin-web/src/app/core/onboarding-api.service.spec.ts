import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { OnboardingApiService, OnboardingStatus } from './onboarding-api.service';

describe('OnboardingApiService', () => {
  let api: OnboardingApiService;
  let http: HttpTestingController;

  const databaseStage: OnboardingStatus = {
    stage: 'DATABASE',
    databaseType: null,
    botCount: 0,
    completedAt: null
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(OnboardingApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads and publishes the current stage', () => {
    api.status().subscribe();

    const pending = http.expectOne('/api/system/onboarding');
    expect(pending.request.method).toBe('GET');
    pending.flush(databaseStage);

    expect(api.currentStatus()).toEqual(databaseStage);
  });

  it('confirms the active database revision and type', () => {
    const botStage: OnboardingStatus = {
      stage: 'BOT',
      databaseType: 'POSTGRESQL',
      botCount: 0,
      completedAt: null
    };

    api.databaseConfigured(3, 'POSTGRESQL').subscribe();

    const pending = http.expectOne('/api/system/onboarding/database-configured');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual({ expectedRevision: 3, databaseType: 'POSTGRESQL' });
    pending.flush(botStage);

    expect(api.currentStatus()).toEqual(botStage);
  });

  it('completes and can clear the cached stage', () => {
    const complete: OnboardingStatus = {
      stage: 'COMPLETE',
      databaseType: 'SQLITE',
      botCount: 1,
      completedAt: '2026-07-17T12:00:00Z'
    };

    api.complete().subscribe();

    const pending = http.expectOne('/api/system/onboarding/complete');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual({});
    pending.flush(complete);
    expect(api.currentStatus()).toEqual(complete);

    api.clear();
    expect(api.currentStatus()).toBeNull();
  });
});
