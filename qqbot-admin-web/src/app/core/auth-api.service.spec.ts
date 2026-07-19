import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthApiService } from './auth-api.service';

describe('AuthApiService', () => {
  let api: AuthApiService;
  let http: HttpTestingController;

  const anonymous = { setupRequired: false, authenticated: false, username: null };
  const authenticated = { setupRequired: false, authenticated: true, username: 'admin' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(AuthApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads and publishes authentication status', () => {
    api.status().subscribe();

    const status = http.expectOne('/api/auth/status');
    expect(status.request.method).toBe('GET');
    status.flush(authenticated);

    expect(api.currentStatus()).toEqual(authenticated);
  });

  it('refreshes status after first-time setup', () => {
    api.setup({ username: 'admin', password: 'long-setup-password' }).subscribe();

    const setup = http.expectOne('/api/auth/setup');
    expect(setup.request.method).toBe('POST');
    expect(setup.request.body).toEqual({
      username: 'admin',
      password: 'long-setup-password'
    });
    setup.flush(authenticated);

    http.expectOne('/api/auth/status').flush(authenticated);
    expect(api.currentStatus()).toEqual(authenticated);
  });

  it('refreshes status after login', () => {
    api.login({ username: 'admin', password: 'long-login-password' }).subscribe();

    const login = http.expectOne('/api/auth/login');
    expect(login.request.method).toBe('POST');
    login.flush(authenticated);

    http.expectOne('/api/auth/status').flush(authenticated);
    expect(api.currentStatus()).toEqual(authenticated);
  });

  it('refreshes status after logout', () => {
    api.logout().subscribe();

    const logout = http.expectOne('/api/auth/logout');
    expect(logout.request.method).toBe('POST');
    expect(logout.request.body).toBeNull();
    logout.flush(anonymous);

    http.expectOne('/api/auth/status').flush(anonymous);
    expect(api.currentStatus()).toEqual(anonymous);
  });
});
