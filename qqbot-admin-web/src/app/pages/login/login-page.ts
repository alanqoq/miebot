import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  LucideAlertTriangle,
  LucideBot,
  LucideEye,
  LucideEyeOff,
  LucideLoaderCircle,
  LucideLogIn,
  LucideRefreshCw,
  LucideShieldCheck,
  LucideUserRoundPlus
} from '@lucide/angular';
import { Observable } from 'rxjs';
import { finalize } from 'rxjs/operators';
import {
  AuthApiService,
  LoginRequest,
  SetupAdminRequest
} from '../../core/auth-api.service';
import { AuthStatus } from '../../core/auth-session.store';

interface ApiErrorBody {
  code?: string;
  message?: string;
}

@Component({
  selector: 'app-login-page',
  imports: [
    ReactiveFormsModule,
    LucideAlertTriangle,
    LucideBot,
    LucideEye,
    LucideEyeOff,
    LucideLoaderCircle,
    LucideLogIn,
    LucideRefreshCw,
    LucideShieldCheck,
    LucideUserRoundPlus
  ],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss'
})
export class LoginPage implements OnInit {
  private readonly auth = inject(AuthApiService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly loading = signal(true);
  protected readonly submitting = signal(false);
  protected readonly setupRequired = signal(false);
  protected readonly passwordVisible = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly form = this.formBuilder.nonNullable.group(
    {
      username: ['', [Validators.required, Validators.maxLength(64)]],
      password: ['', [Validators.required, Validators.maxLength(72)]],
      confirmPassword: ['']
    },
    {
      validators: (control) => {
        if (!this.setupRequired()) {
          return null;
        }
        const password = control.get('password')?.value;
        const confirmation = control.get('confirmPassword')?.value;
        return password === confirmation ? null : { passwordMismatch: true };
      }
    }
  );

  ngOnInit(): void {
    const current = this.auth.currentStatus();
    if (current) {
      this.applyStatus(current);
      this.loading.set(false);
      return;
    }
    this.loadStatus();
  }

  protected loadStatus(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.auth
      .status()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (status) => this.applyStatus(status),
        error: () => {
          this.errorMessage.set('无法连接管理服务，请确认服务已启动后重试。');
        }
      });
  }

  protected submit(): void {
    this.errorMessage.set(null);
    this.form.markAllAsTouched();
    this.form.updateValueAndValidity();
    if (this.form.invalid || this.submitting()) {
      return;
    }

    const username = this.form.controls.username.value.trim();
    const password = this.form.controls.password.value;
    const firstSetup = this.setupRequired();
    const operation: Observable<AuthStatus> = firstSetup
      ? this.auth.setup({ username, password } satisfies SetupAdminRequest)
      : this.auth.login({ username, password } satisfies LoginRequest);

    this.clearPasswords();
    this.submitting.set(true);
    operation.pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: (status) => {
        if (status.authenticated) {
          void this.router.navigateByUrl(firstSetup ? '/setup' : this.safeReturnUrl());
        } else {
          this.applyStatus(status);
          this.errorMessage.set('认证状态未生效，请重试。');
        }
      },
      error: (error: unknown) => this.handleSubmitError(error)
    });
  }

  protected togglePasswordVisibility(): void {
    this.passwordVisible.update((visible) => !visible);
  }

  private applyStatus(status: AuthStatus): void {
    if (status.authenticated) {
      void this.router.navigateByUrl(this.safeReturnUrl());
      return;
    }
    this.setupRequired.set(status.setupRequired);
    this.configureValidators(status.setupRequired);
  }

  private configureValidators(setup: boolean): void {
    if (setup && this.form.controls.username.value.length === 0) {
      this.form.controls.username.setValue('admin', { emitEvent: false });
    }
    this.form.controls.username.setValidators(
      setup
        ? [
            Validators.required,
            Validators.minLength(3),
            Validators.maxLength(64),
            Validators.pattern(/^[A-Za-z0-9._-]+$/)
          ]
        : [Validators.required, Validators.maxLength(64)]
    );
    this.form.controls.password.setValidators(
      setup
        ? [Validators.required, Validators.minLength(12), Validators.maxLength(72)]
        : [Validators.required, Validators.maxLength(72)]
    );
    this.form.controls.confirmPassword.setValidators(setup ? [Validators.required] : []);
    this.form.controls.username.updateValueAndValidity({ emitEvent: false });
    this.form.controls.password.updateValueAndValidity({ emitEvent: false });
    this.form.controls.confirmPassword.updateValueAndValidity({ emitEvent: false });
    this.form.updateValueAndValidity({ emitEvent: false });
  }

  private handleSubmitError(error: unknown): void {
    if (!(error instanceof HttpErrorResponse)) {
      this.errorMessage.set('认证请求失败，请稍后重试。');
      return;
    }
    if (error.status === 0) {
      this.errorMessage.set('无法连接管理服务，请检查网络或服务状态。');
      return;
    }
    if (error.status === 401) {
      this.errorMessage.set('用户名或密码不正确。');
      return;
    }
    if (error.status === 409) {
      this.errorMessage.set('管理员已完成初始化，请使用管理员账号登录。');
      this.auth.status().subscribe({
        next: (status) => this.applyStatus(status),
        error: () => undefined
      });
      return;
    }
    if (error.status === 429) {
      const retryAfter = Number.parseInt(error.headers.get('Retry-After') ?? '', 10);
      this.errorMessage.set(
        Number.isFinite(retryAfter) && retryAfter > 0
          ? `登录尝试过于频繁，请在 ${retryAfter} 秒后重试。`
          : '登录尝试过于频繁，请稍后重试。'
      );
      return;
    }

    const body = error.error as ApiErrorBody | null;
    this.errorMessage.set(body?.message || '认证请求失败，请稍后重试。');
  }

  private clearPasswords(): void {
    this.form.controls.password.setValue('');
    this.form.controls.confirmPassword.setValue('');
    this.form.controls.password.markAsUntouched();
    this.form.controls.confirmPassword.markAsUntouched();
  }

  private safeReturnUrl(): string {
    const requested = this.route.snapshot.queryParamMap.get('returnUrl');
    if (
      requested &&
      requested.startsWith('/') &&
      !requested.startsWith('//') &&
      !requested.startsWith('/login')
    ) {
      return requested;
    }
    return '/dashboard';
  }
}
