import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthApiService } from '../../core/auth-api.service';
import { AuthStatus } from '../../core/auth-session.store';
import { LoginPage } from './login-page';

describe('LoginPage', () => {
  const currentStatus = signal<AuthStatus | null>(null);
  const status = vi.fn();
  const setup = vi.fn();
  const login = vi.fn();

  beforeEach(async () => {
    currentStatus.set(null);
    status.mockReset();
    setup.mockReset();
    login.mockReset();
    status.mockReturnValue(
      of({ setupRequired: false, authenticated: false, username: null } satisfies AuthStatus)
    );

    await TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [
        provideRouter([]),
        {
          provide: AuthApiService,
          useValue: { currentStatus, status, setup, login }
        }
      ]
    }).compileComponents();
  });

  it('creates the first administrator and clears password controls immediately', () => {
    currentStatus.set({ setupRequired: true, authenticated: false, username: null });
    setup.mockReturnValue(
      of({ setupRequired: false, authenticated: true, username: 'admin' } satisfies AuthStatus)
    );
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="username"]', 'admin');
    fill(fixture, 'input[formControlName="password"]', 'long-setup-password');
    fill(fixture, 'input[formControlName="confirmPassword"]', 'long-setup-password');
    submitForm(fixture);

    expect(setup).toHaveBeenCalledWith({
      username: 'admin',
      password: 'long-setup-password'
    });
    expect(inputValue(fixture, 'input[formControlName="password"]')).toBe('');
    expect(inputValue(fixture, 'input[formControlName="confirmPassword"]')).toBe('');
    expect(navigate).toHaveBeenCalledWith('/setup');
  });

  it('shows a clear invalid-credentials message for 401', () => {
    currentStatus.set({ setupRequired: false, authenticated: false, username: null });
    login.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }))
    );
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="username"]', 'admin');
    fill(fixture, 'input[formControlName="password"]', 'wrong-password');
    submitForm(fixture);

    expect(fixture.nativeElement.textContent).toContain('用户名或密码不正确');
  });

  it('switches from setup to login after a 409 conflict', () => {
    currentStatus.set({ setupRequired: true, authenticated: false, username: null });
    setup.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409, statusText: 'Conflict' }))
    );
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="username"]', 'admin');
    fill(fixture, 'input[formControlName="password"]', 'long-setup-password');
    fill(fixture, 'input[formControlName="confirmPassword"]', 'long-setup-password');
    submitForm(fixture);

    expect(status).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('管理员已完成初始化');
    expect(fixture.nativeElement.textContent).toContain('管理员登录');
  });

  it('honors Retry-After when login is throttled', () => {
    currentStatus.set({ setupRequired: false, authenticated: false, username: null });
    login.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 429,
            statusText: 'Too Many Requests',
            headers: new HttpHeaders({ 'Retry-After': '30' })
          })
      )
    );
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="username"]', 'admin');
    fill(fixture, 'input[formControlName="password"]', 'long-login-password');
    submitForm(fixture);

    expect(fixture.nativeElement.textContent).toContain('请在 30 秒后重试');
  });

  it('offers a retry state when the management service is unreachable', () => {
    status.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 0, statusText: 'Unknown Error' }))
    );

    const fixture = createFixture();

    expect(fixture.nativeElement.textContent).toContain('无法连接管理服务');
    expect(fixture.nativeElement.textContent).toContain('重试');
  });
});

function createFixture(): ComponentFixture<LoginPage> {
  const fixture = TestBed.createComponent(LoginPage);
  fixture.detectChanges();
  fixture.detectChanges();
  return fixture;
}

function fill(fixture: ComponentFixture<LoginPage>, selector: string, value: string): void {
  const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  fixture.detectChanges();
}

function submitForm(fixture: ComponentFixture<LoginPage>): void {
  const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  fixture.detectChanges();
}

function inputValue(fixture: ComponentFixture<LoginPage>, selector: string): string {
  return (fixture.nativeElement.querySelector(selector) as HTMLInputElement).value;
}
