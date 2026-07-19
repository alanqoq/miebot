import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { App } from './app';
import { AuthApiService } from './core/auth-api.service';
import { AuthStatus } from './core/auth-session.store';
import { OnboardingApiService } from './core/onboarding-api.service';
import { SystemApiService } from './core/system-api.service';

describe('App', () => {
  const currentStatus = signal<AuthStatus | null>(null);
  const logout = vi.fn();
  const getReadiness = vi.fn();
  const ensureDatabaseConfiguration = vi.fn();

  beforeEach(async () => {
    currentStatus.set(null);
    logout.mockReset();
    getReadiness.mockReset().mockReturnValue(
      of({ status: 'UP', checkedAt: '2026-07-18T12:00:00Z', components: {} }),
    );
    ensureDatabaseConfiguration.mockReset().mockReturnValue(of({
      revision: 1,
      type: 'SQLITE',
      sqlitePath: 'qqbot.db',
      busyTimeoutMs: 5000,
      host: null,
      port: null,
      databaseName: null,
      username: null,
      sslMode: null,
      connectTimeoutMs: null,
      passwordConfigured: false,
      databaseProduct: 'SQLite',
      databaseVersion: '3.49.1',
      switchInProgress: false,
      lastSwitchedAt: null,
    }));
    logout.mockReturnValue(
      of({ setupRequired: false, authenticated: false, username: null } satisfies AuthStatus)
    );
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: AuthApiService, useValue: { currentStatus, logout } },
        { provide: OnboardingApiService, useValue: { clear: vi.fn() } },
        { provide: SystemApiService, useValue: { getReadiness, ensureDatabaseConfiguration } },
      ]
    }).compileComponents();
  });

  it('hides the administration shell before authentication', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.sidebar')).toBeNull();
  });

  it('shows the administration shell and explicit logout action after authentication', async () => {
    currentStatus.set({ setupRequired: false, authenticated: true, username: 'admin' });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('QQ Bot Control');
    expect(fixture.nativeElement.textContent).toContain('admin');
    expect(fixture.nativeElement.querySelector('.logout-button').textContent).toContain('注销');
    expect(fixture.nativeElement.textContent).toContain('管理服务正常');
    expect(fixture.nativeElement.textContent).toContain('SQLite / Local');
    expect(ensureDatabaseConfiguration).toHaveBeenCalledTimes(1);
  });

  it('reports an unavailable database label when shell configuration loading fails', async () => {
    ensureDatabaseConfiguration.mockReturnValue(throwError(() => new Error('database unavailable')));
    currentStatus.set({ setupRequired: false, authenticated: true, username: 'admin' });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Database / Unavailable');
  });
});
