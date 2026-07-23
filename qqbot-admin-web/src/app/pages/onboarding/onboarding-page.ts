import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { Router } from '@angular/router';
import {
  LucideAlertTriangle,
  LucideArrowRight,
  LucideBot,
  LucideCheck,
  LucideDatabase,
  LucideEye,
  LucideEyeOff,
  LucideHardDrive,
  LucideLoaderCircle,
  LucidePlus,
  LucideServer,
  LucideShieldCheck,
} from '@lucide/angular';
import { forkJoin } from 'rxjs';
import { finalize } from 'rxjs/operators';
import {
  BotApiService,
  BotConfiguration,
  BotEnvironment,
  CreateBotRequest,
} from '../../core/bot-api.service';
import { DEFAULT_GATEWAY_INTENTS } from '../../core/gateway-intents';
import { OnboardingApiService } from '../../core/onboarding-api.service';
import {
  DatabaseCandidate,
  DatabaseConfiguration,
  DatabaseSslMode,
  DatabaseType,
  SystemApiService,
} from '../../core/system-api.service';

type SetupStep = 'DATABASE' | 'BOT';

interface DatabaseFormValue {
  type: DatabaseType;
  sqlitePath: string;
  busyTimeoutMs: number;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password: string;
  sslMode: DatabaseSslMode;
  connectTimeoutMs: number;
}

const trimmedText: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  return typeof value === 'string' && value.length > 0 && value !== value.trim()
    ? { surroundingWhitespace: true }
    : null;
};

const safeInteger: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  return typeof value === 'number' && Number.isSafeInteger(value) ? null : { integer: true };
};

@Component({
  selector: 'app-onboarding-page',
  imports: [
    ReactiveFormsModule,
    LucideAlertTriangle,
    LucideArrowRight,
    LucideBot,
    LucideCheck,
    LucideDatabase,
    LucideEye,
    LucideEyeOff,
    LucideHardDrive,
    LucideLoaderCircle,
    LucidePlus,
    LucideServer,
    LucideShieldCheck,
  ],
  templateUrl: './onboarding-page.html',
  styleUrl: './onboarding-page.scss',
})
export class OnboardingPage implements OnInit {
  private readonly onboarding = inject(OnboardingApiService);
  private readonly systemApi = inject(SystemApiService);
  private readonly botApi = inject(BotApiService);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly loading = signal(true);
  protected readonly pageError = signal<string | null>(null);
  protected readonly step = signal<SetupStep>('DATABASE');
  protected readonly configuration = signal<DatabaseConfiguration | null>(null);
  protected readonly selectedType = signal<DatabaseType>('SQLITE');
  protected readonly databaseSaving = signal(false);
  protected readonly databaseError = signal<string | null>(null);
  protected readonly databaseAppliedRevision = signal<number | null>(null);
  protected readonly databasePasswordVisible = signal(false);
  protected readonly botSecretVisible = signal(false);
  protected readonly botSaving = signal(false);
  protected readonly completing = signal(false);
  protected readonly botError = signal<string | null>(null);
  protected readonly createdBots = signal<BotConfiguration[]>([]);
  protected readonly addingAnother = signal(true);
  protected readonly activeDatabaseType = signal<DatabaseType>('SQLITE');

  protected readonly databaseForm = this.formBuilder.group({
    type: ['SQLITE' as DatabaseType],
    sqlitePath: ['qqbot.db'],
    busyTimeoutMs: [5000],
    host: ['localhost'],
    port: [3306],
    databaseName: ['qqbot'],
    username: ['qqbot'],
    password: ['', Validators.maxLength(4096)],
    sslMode: ['PREFERRED' as DatabaseSslMode],
    connectTimeoutMs: [5000],
  });

  protected readonly botForm = this.formBuilder.group({
    appId: ['', [Validators.required, Validators.maxLength(128), Validators.pattern(/^\S+$/)]],
    environment: ['PRODUCTION' as BotEnvironment, Validators.required],
    appSecret: ['', [Validators.required, Validators.maxLength(4096)]],
  });

  ngOnInit(): void {
    this.configureDatabaseValidators('SQLITE');
    this.databaseForm.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.databaseAppliedRevision.set(null);
      this.databaseError.set(null);
    });
    this.load();
  }

  protected retryLoad(): void {
    this.load();
  }

  protected selectDatabaseType(type: DatabaseType): void {
    if (this.selectedType() === type) {
      return;
    }
    const previousPort = this.databaseForm.controls.port.value;
    this.selectedType.set(type);
    this.databaseForm.controls.type.setValue(type);
    if (type === 'MYSQL' && (previousPort === 5432 || previousPort < 1)) {
      this.databaseForm.controls.port.setValue(3306);
    } else if (type === 'POSTGRESQL' && (previousPort === 3306 || previousPort < 1)) {
      this.databaseForm.controls.port.setValue(5432);
    }
    this.configureDatabaseValidators(type);
  }

  protected saveDatabase(): void {
    if (this.databaseSaving()) {
      return;
    }
    const alreadyApplied = this.databaseAppliedRevision();
    if (alreadyApplied !== null) {
      this.markDatabaseConfigured(alreadyApplied, this.selectedType());
      return;
    }

    const active = this.configuration();
    const candidate = this.buildDatabaseCandidate();
    if (!active || !candidate) {
      return;
    }

    this.databaseSaving.set(true);
    this.databaseError.set(null);
    this.systemApi.switchDatabase(active.revision, candidate).subscribe({
      next: (result) => {
        this.configuration.set(result.configuration);
        this.activeDatabaseType.set(result.configuration.type);
        this.databaseForm.controls.password.setValue('', { emitEvent: false });
        this.databaseAppliedRevision.set(result.configuration.revision);
        this.markDatabaseConfigured(result.configuration.revision, result.configuration.type);
      },
      error: (error: unknown) => {
        this.databaseSaving.set(false);
        this.databaseError.set(this.databaseErrorMessage(error));
      },
    });
  }

  protected saveBot(): void {
    this.botError.set(null);
    this.botForm.markAllAsTouched();
    if (this.botForm.invalid || this.botSaving()) {
      return;
    }
    const value = this.botForm.getRawValue();
    const request: CreateBotRequest = {
      displayName: `QQ Bot ${this.createdBots().length + 1}`,
      appId: value.appId.trim(),
      environment: value.environment,
      intents: DEFAULT_GATEWAY_INTENTS,
      shardIndex: 0,
      shardCount: 1,
      enabled: true,
      appSecret: value.appSecret,
      maxMediaUploadBytes: 16 * 1024 * 1024,
    };

    this.botSaving.set(true);
    this.botApi
      .create(request)
      .pipe(finalize(() => this.botSaving.set(false)))
      .subscribe({
        next: (bot) => {
          this.createdBots.update((bots) => [...bots, bot]);
          this.botForm.reset({
            appId: '',
            environment: value.environment,
            appSecret: '',
          });
          this.botForm.markAsPristine();
          this.botForm.markAsUntouched();
          this.botSecretVisible.set(false);
          this.addingAnother.set(false);
          if (this.activeDatabaseType() === 'SQLITE') {
            this.completeSetup();
          }
        },
        error: (error: unknown) => {
          this.botError.set(this.botErrorMessage(error));
        },
      });
  }

  protected addAnotherBot(): void {
    if (this.activeDatabaseType() !== 'SQLITE') {
      this.addingAnother.set(true);
      this.botError.set(null);
    }
  }

  protected completeSetup(): void {
    if (this.completing() || this.createdBots().length === 0) {
      return;
    }
    this.completing.set(true);
    this.botError.set(null);
    this.onboarding
      .complete()
      .pipe(finalize(() => this.completing.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl('/'),
        error: (error: unknown) => {
          this.botError.set(this.genericError(error, '无法完成首次设置，请重试。'));
        },
      });
  }

  protected toggleDatabasePassword(): void {
    this.databasePasswordVisible.update((visible) => !visible);
  }

  protected toggleBotSecret(): void {
    this.botSecretVisible.update((visible) => !visible);
  }

  protected databaseTypeLabel(type: DatabaseType): string {
    return type === 'SQLITE' ? 'SQLite' : type === 'MYSQL' ? 'MySQL' : 'PostgreSQL';
  }

  protected fieldError(control: AbstractControl, label: string): string | null {
    if (!control.touched || !control.errors) {
      return null;
    }
    if (control.hasError('required')) {
      return `${label}不能为空`;
    }
    if (control.hasError('surroundingWhitespace')) {
      return `${label}首尾不能有空格`;
    }
    if (control.hasError('pattern')) {
      return `${label}格式不正确`;
    }
    if (control.hasError('integer')) {
      return `${label}必须是整数`;
    }
    if (control.hasError('min') || control.hasError('max')) {
      return `${label}超出允许范围`;
    }
    if (control.hasError('maxlength')) {
      return `${label}长度超出限制`;
    }
    return `${label}无效`;
  }

  private load(): void {
    this.loading.set(true);
    this.pageError.set(null);
    forkJoin({
      onboarding: this.onboarding.status(),
      configuration: this.systemApi.getDatabaseConfiguration(),
      bots: this.botApi.list(),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ onboarding, configuration, bots }) => {
          if (onboarding.stage === 'COMPLETE') {
            void this.router.navigateByUrl('/');
            return;
          }
          this.configuration.set(configuration);
          this.activeDatabaseType.set(onboarding.databaseType ?? configuration.type);
          this.createdBots.set(bots);
          this.populateDatabaseForm(configuration);
          if (onboarding.stage === 'BOT') {
            this.step.set('BOT');
            this.addingAnother.set(bots.length === 0);
          } else {
            this.step.set('DATABASE');
          }
        },
        error: (error: unknown) => {
          this.pageError.set(this.genericError(error, '无法加载首次设置状态。'));
        },
      });
  }

  private markDatabaseConfigured(revision: number, type: DatabaseType): void {
    this.databaseSaving.set(true);
    this.onboarding
      .databaseConfigured(revision, type)
      .pipe(finalize(() => this.databaseSaving.set(false)))
      .subscribe({
        next: () => {
          this.databaseAppliedRevision.set(null);
          this.activeDatabaseType.set(type);
          this.step.set('BOT');
          this.addingAnother.set(this.createdBots().length === 0);
          this.databasePasswordVisible.set(false);
        },
        error: (error: unknown) => {
          this.databaseAppliedRevision.set(revision);
          this.databaseError.set(
            this.genericError(error, '数据库已经生效，但向导状态保存失败，请重试。'),
          );
        },
      });
  }

  private populateDatabaseForm(configuration: DatabaseConfiguration): void {
    const type = configuration.type;
    this.selectedType.set(type);
    this.configureDatabaseValidators(type);
    this.databaseForm.patchValue(
      {
        type,
        sqlitePath: configuration.sqlitePath ?? this.databaseForm.controls.sqlitePath.value,
        busyTimeoutMs:
          configuration.busyTimeoutMs ?? this.databaseForm.controls.busyTimeoutMs.value,
        host: configuration.host ?? this.databaseForm.controls.host.value,
        port: configuration.port ?? (type === 'POSTGRESQL' ? 5432 : 3306),
        databaseName: configuration.databaseName ?? this.databaseForm.controls.databaseName.value,
        username: configuration.username ?? this.databaseForm.controls.username.value,
        password: '',
        sslMode: configuration.sslMode ?? 'PREFERRED',
        connectTimeoutMs:
          configuration.connectTimeoutMs ?? this.databaseForm.controls.connectTimeoutMs.value,
      },
      { emitEvent: false },
    );
    this.databaseForm.markAsPristine();
    this.databaseForm.markAsUntouched();
  }

  private configureDatabaseValidators(type: DatabaseType): void {
    const sqlite = type === 'SQLITE';
    this.databaseForm.controls.sqlitePath.setValidators(
      sqlite
        ? [Validators.required, Validators.maxLength(4096), trimmedText]
        : [Validators.maxLength(4096), trimmedText],
    );
    this.databaseForm.controls.busyTimeoutMs.setValidators(
      sqlite
        ? [Validators.required, Validators.min(100), Validators.max(60000), safeInteger]
        : [Validators.min(100), Validators.max(60000), safeInteger],
    );
    this.databaseForm.controls.host.setValidators(
      sqlite
        ? [Validators.maxLength(253), trimmedText]
        : [
            Validators.required,
            Validators.maxLength(253),
            trimmedText,
            Validators.pattern(/^[^\s/?#@&=\\]+$/),
          ],
    );
    const serverText = [Validators.required, Validators.maxLength(128), trimmedText];
    this.databaseForm.controls.databaseName.setValidators(
      sqlite ? [Validators.maxLength(128)] : serverText,
    );
    this.databaseForm.controls.username.setValidators(
      sqlite ? [Validators.maxLength(128)] : serverText,
    );
    this.databaseForm.controls.port.setValidators(
      sqlite
        ? [Validators.min(1), Validators.max(65535), safeInteger]
        : [Validators.required, Validators.min(1), Validators.max(65535), safeInteger],
    );
    this.databaseForm.controls.connectTimeoutMs.setValidators(
      sqlite
        ? [Validators.min(500), Validators.max(60000), safeInteger]
        : [Validators.required, Validators.min(500), Validators.max(60000), safeInteger],
    );
    for (const control of [
      this.databaseForm.controls.sqlitePath,
      this.databaseForm.controls.busyTimeoutMs,
      this.databaseForm.controls.host,
      this.databaseForm.controls.port,
      this.databaseForm.controls.databaseName,
      this.databaseForm.controls.username,
      this.databaseForm.controls.connectTimeoutMs,
    ]) {
      control.updateValueAndValidity({ emitEvent: false });
    }
  }

  private buildDatabaseCandidate(): DatabaseCandidate | null {
    this.databaseForm.markAllAsTouched();
    const value = this.databaseForm.getRawValue();
    const passwordControl = this.databaseForm.controls.password;
    if (value.type !== 'SQLITE' && value.password.length === 0 && !this.canReusePassword(value)) {
      passwordControl.setErrors({ ...(passwordControl.errors ?? {}), required: true });
    } else if (passwordControl.hasError('required')) {
      const errors = { ...(passwordControl.errors ?? {}) };
      delete errors['required'];
      passwordControl.setErrors(Object.keys(errors).length > 0 ? errors : null);
    }
    if (this.databaseForm.invalid) {
      this.databaseError.set('请修正数据库配置后重试。');
      return null;
    }

    if (value.type === 'SQLITE') {
      return {
        type: value.type,
        sqlitePath: value.sqlitePath.trim(),
        busyTimeoutMs: value.busyTimeoutMs,
      };
    }
    return {
      type: value.type,
      host: value.host.trim(),
      port: value.port,
      databaseName: value.databaseName.trim(),
      username: value.username.trim(),
      ...(value.password.length > 0 ? { password: value.password } : {}),
      sslMode: value.sslMode,
      connectTimeoutMs: value.connectTimeoutMs,
    };
  }

  private canReusePassword(value: DatabaseFormValue): boolean {
    const active = this.configuration();
    return (
      active !== null &&
      active.type === value.type &&
      active.type !== 'SQLITE' &&
      active.host === value.host.trim() &&
      active.port === value.port &&
      active.databaseName === value.databaseName.trim() &&
      active.username === value.username.trim() &&
      active.passwordConfigured
    );
  }

  private databaseErrorMessage(error: unknown): string {
    if (!(error instanceof HttpErrorResponse)) {
      return '数据库验证失败，请重新填写。';
    }
    const code = typeof error.error?.code === 'string' ? error.error.code : '';
    const messages: Record<string, string> = {
      DATABASE_CONNECTION_FAILED: '无法连接数据库，请检查地址、端口、数据库名、用户名和密码。',
      DATABASE_READ_FAILED: '数据库读取检查失败，请检查账号权限。',
      DATABASE_WRITE_FAILED: '数据库写入检查失败，请为账号授予读写和建表权限。',
      TARGET_SCHEMA_INCOMPATIBLE: '目标数据库已有不兼容的表结构，请更换空数据库。',
      DATABASE_CONFIG_CONFLICT: '数据库配置已变化，请刷新后重新设置。',
      APP_SECRET_KEY_UNAVAILABLE: '主密钥不可用，无法安全保存数据库密码。',
    };
    return messages[code] ?? this.genericError(error, '数据库验证失败，请重新填写。');
  }

  private botErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      const code = typeof error.error?.code === 'string' ? error.error.code : '';
      if (code === 'APP_SECRET_KEY_UNAVAILABLE') {
        return '主密钥不可用，无法安全保存 AppSecret。';
      }
      if (error.status === 409) {
        return '该 AppID 已存在，请重新填写。';
      }
    }
    return this.genericError(error, '机器人保存失败，请重试。');
  }

  private genericError(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse && error.status === 0) {
      return '无法连接管理服务。';
    }
    return fallback;
  }
}
