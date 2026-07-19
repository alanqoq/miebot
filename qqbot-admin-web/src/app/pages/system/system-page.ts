import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators
} from '@angular/forms';
import {
  LucideActivity,
  LucideClipboardList,
  LucideDatabase,
  LucideGlobe,
  LucideHardDrive,
  LucideKeyRound,
  LucideRefreshCw,
  LucideServer,
  LucideShieldCheck
} from '@lucide/angular';
import { AuthApiService } from '../../core/auth-api.service';
import { finalize } from 'rxjs/operators';
import {
  DatabaseCandidate,
  AuditLogEntry,
  DatabaseConfiguration,
  DatabaseSchemaState,
  DatabaseSslMode,
  DatabaseTestResult,
  DatabaseType,
  SystemApiService
} from '../../core/system-api.service';

type PendingAction = 'switch' | 'reload';

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

const matchingPasswords: ValidatorFn = (control: AbstractControl): ValidationErrors | null =>
  control.get('newPassword')?.value === control.get('confirmPassword')?.value
    ? null
    : { passwordMismatch: true };

@Component({
  selector: 'app-system-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    LucideActivity,
    LucideClipboardList,
    LucideDatabase,
    LucideGlobe,
    LucideHardDrive,
    LucideKeyRound,
    LucideRefreshCw,
    LucideServer,
    LucideShieldCheck
  ],
  templateUrl: './system-page.html',
  styleUrl: './system-page.scss'
})
export class SystemPage implements OnInit {
  private readonly api = inject(SystemApiService);
  protected readonly authApi = inject(AuthApiService);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly configuration = signal<DatabaseConfiguration | null>(null);
  protected readonly selectedType = signal<DatabaseType>('SQLITE');
  protected readonly loading = signal(true);
  protected readonly testing = signal(false);
  protected readonly switching = signal(false);
  protected readonly reloadingFile = signal(false);
  protected readonly pageError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionSuccess = signal<string | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly fileSuccess = signal<string | null>(null);
  protected readonly testResult = signal<DatabaseTestResult | null>(null);
  protected readonly pendingAction = signal<PendingAction | null>(null);
  protected readonly changingPassword = signal(false);
  protected readonly passwordError = signal<string | null>(null);
  protected readonly passwordSuccess = signal<string | null>(null);
  protected readonly auditLogs = signal<AuditLogEntry[]>([]);
  protected readonly auditCursor = signal<string | null>(null);
  protected readonly auditHasMore = signal(false);
  protected readonly auditLoading = signal(false);
  protected readonly auditError = signal<string | null>(null);
  private readonly formVersion = signal(0);
  private readonly testedFormVersion = signal<number | null>(null);

  protected readonly form = this.formBuilder.group({
    type: ['SQLITE' as DatabaseType],
    sqlitePath: ['qqbot.db'],
    busyTimeoutMs: [5000],
    host: ['localhost'],
    port: [3306],
    databaseName: ['qqbot'],
    username: ['qqbot'],
    password: ['', Validators.maxLength(4096)],
    sslMode: ['PREFERRED' as DatabaseSslMode],
    connectTimeoutMs: [5000]
  });

  protected readonly passwordForm = this.formBuilder.group({
    currentPassword: ['', [Validators.required, Validators.maxLength(4096)]],
    newPassword: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(4096)]],
    confirmPassword: ['', [Validators.required, Validators.maxLength(4096)]],
  }, { validators: matchingPasswords });

  protected readonly passwordRequired = computed(() => {
    this.formVersion();
    const active = this.configuration();
    const value = this.form.getRawValue();
    if (value.type === 'SQLITE') {
      return false;
    }
    return (
      !active ||
      active.type !== value.type ||
      active.host !== value.host.trim() ||
      active.port !== value.port ||
      active.databaseName !== value.databaseName.trim() ||
      active.username !== value.username.trim() ||
      !active.passwordConfigured
    );
  });

  protected readonly switchReady = computed(
    () =>
      this.testResult()?.success === true &&
      this.testedFormVersion() === this.formVersion() &&
      !this.testing() &&
      !this.switching() &&
      !this.reloadingFile()
  );

  ngOnInit(): void {
    this.configureValidators('SQLITE');
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.formVersion.update((version) => version + 1);
      this.testResult.set(null);
      this.testedFormVersion.set(null);
      this.pendingAction.set(null);
      this.actionError.set(null);
      this.actionSuccess.set(null);
    });
    this.loadConfiguration(false);
    this.loadAuditLogs(false);
  }

  protected selectType(type: DatabaseType): void {
    if (this.selectedType() === type) {
      return;
    }
    const previousPort = this.form.controls.port.value;
    this.selectedType.set(type);
    this.form.controls.type.setValue(type);
    if (type === 'MYSQL' && (previousPort === 5432 || previousPort < 1)) {
      this.form.controls.port.setValue(3306);
    } else if (type === 'POSTGRESQL' && (previousPort === 3306 || previousPort < 1)) {
      this.form.controls.port.setValue(5432);
    }
    this.configureValidators(type);
  }

  protected testDatabase(): void {
    const candidate = this.buildCandidate();
    if (!candidate || this.testing()) {
      return;
    }
    const testedVersion = this.formVersion();
    this.testing.set(true);
    this.actionError.set(null);
    this.actionSuccess.set(null);
    this.pendingAction.set(null);
    this.api
      .testDatabase(candidate)
      .pipe(finalize(() => this.testing.set(false)))
      .subscribe({
        next: (result) => {
          if (this.formVersion() !== testedVersion) {
            return;
          }
          this.testResult.set(result);
          this.testedFormVersion.set(testedVersion);
          this.actionSuccess.set('连接、读取和写入检查已通过。');
        },
        error: (error: unknown) => {
          this.testResult.set(null);
          this.testedFormVersion.set(null);
          this.actionError.set(this.errorMessage(error, '数据库检查失败。'));
        }
      });
  }

  protected requestSwitch(): void {
    if (this.switchReady()) {
      this.pendingAction.set('switch');
      this.actionError.set(null);
      this.actionSuccess.set(null);
    }
  }

  protected confirmSwitch(): void {
    const active = this.configuration();
    const candidate = this.buildCandidate();
    if (!active || !candidate || !this.switchReady() || this.switching()) {
      return;
    }
    this.switching.set(true);
    this.pendingAction.set(null);
    this.actionError.set(null);
    this.api
      .switchDatabase(active.revision, candidate)
      .pipe(finalize(() => this.switching.set(false)))
      .subscribe({
        next: (result) => {
          this.configuration.set(result.configuration);
          this.populateForm(result.configuration);
          this.testResult.set(result.verification);
          this.actionSuccess.set(`已切换到 ${this.databaseTypeLabel(result.configuration.type)}。`);
        },
        error: (error: unknown) => this.handleMutationError(error, '数据库切换失败。')
      });
  }

  protected requestFileReload(): void {
    if (!this.reloadingFile() && !this.switching()) {
      this.pendingAction.set('reload');
      this.fileError.set(null);
      this.fileSuccess.set(null);
    }
  }

  protected confirmFileReload(): void {
    const active = this.configuration();
    if (!active || this.reloadingFile()) {
      return;
    }
    this.reloadingFile.set(true);
    this.pendingAction.set(null);
    this.fileError.set(null);
    this.fileSuccess.set(null);
    this.api
      .reloadDatabase(active.revision)
      .pipe(finalize(() => this.reloadingFile.set(false)))
      .subscribe({
        next: (result) => {
          this.configuration.set(result.configuration);
          this.populateForm(result.configuration);
          this.testResult.set(result.verification);
          this.fileSuccess.set('候选配置已验证并生效。');
        },
        error: (error: unknown) => this.handleFileMutationError(error)
      });
  }

  protected cancelPendingAction(): void {
    this.pendingAction.set(null);
  }

  protected retryLoad(): void {
    this.loadConfiguration(true);
  }

  protected databaseTypeLabel(type: DatabaseType): string {
    return type === 'SQLITE' ? 'SQLite' : type === 'MYSQL' ? 'MySQL' : 'PostgreSQL';
  }

  protected schemaStateLabel(state: DatabaseSchemaState): string {
    return state === 'EMPTY' ? '空库' : state === 'INITIALIZED' ? '待迁移' : '就绪';
  }

  protected activeLocation(configuration: DatabaseConfiguration): string {
    if (configuration.type === 'SQLITE') {
      return configuration.sqlitePath ?? '未报告路径';
    }
    return `${configuration.host ?? 'unknown'}:${configuration.port ?? 0}/${configuration.databaseName ?? ''}`;
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

  protected changePassword(): void {
    this.passwordError.set(null);
    this.passwordSuccess.set(null);
    if (this.passwordForm.invalid || this.changingPassword()) {
      this.passwordForm.markAllAsTouched();
      return;
    }
    const value = this.passwordForm.getRawValue();
    this.changingPassword.set(true);
    this.authApi.changePassword({
      currentPassword: value.currentPassword,
      newPassword: value.newPassword,
    }).pipe(finalize(() => this.changingPassword.set(false))).subscribe({
      next: () => {
        this.passwordForm.reset();
        this.passwordSuccess.set('后台密码已更新。');
      },
      error: (error: unknown) => {
        const invalidCurrent = error instanceof HttpErrorResponse
          && error.error?.code === 'INVALID_CREDENTIALS';
        this.passwordError.set(invalidCurrent
          ? '当前密码不正确。'
          : this.errorMessage(error, '密码更新失败，请稍后重试。'));
      },
    });
  }

  protected refreshAuditLogs(): void {
    this.loadAuditLogs(false);
  }

  protected loadMoreAuditLogs(): void {
    if (this.auditHasMore() && this.auditCursor()) this.loadAuditLogs(true);
  }

  protected auditActionLabel(action: string): string {
    return ({ POST: '创建/执行', PUT: '更新', PATCH: '状态变更', DELETE: '删除' } as Record<string, string>)[action]
      ?? action;
  }

  private loadConfiguration(force: boolean): void {
    this.loading.set(true);
    this.pageError.set(null);
    const request = force
      ? this.api.getDatabaseConfiguration()
      : this.api.ensureDatabaseConfiguration();
    request.pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (configuration) => {
        this.configuration.set(configuration);
        this.populateForm(configuration);
      },
      error: (error: unknown) => {
        this.pageError.set(this.errorMessage(error, '无法加载数据库配置。'));
      }
    });
  }

  private loadAuditLogs(append: boolean): void {
    if (this.auditLoading()) return;
    this.auditLoading.set(true);
    this.auditError.set(null);
    this.api.listAuditLogs(20, append ? this.auditCursor() : null)
      .pipe(finalize(() => this.auditLoading.set(false)), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.auditLogs.update((current) => append ? [...current, ...page.items] : page.items);
          this.auditCursor.set(page.nextCursor);
          this.auditHasMore.set(page.hasMore);
        },
        error: (error: unknown) => this.auditError.set(
          this.errorMessage(error, '无法读取审计日志。'),
        ),
      });
  }

  private populateForm(configuration: DatabaseConfiguration): void {
    const type = configuration.type;
    this.selectedType.set(type);
    this.configureValidators(type);
    this.form.patchValue(
      {
        type,
        sqlitePath: configuration.sqlitePath ?? this.form.controls.sqlitePath.value,
        busyTimeoutMs: configuration.busyTimeoutMs ?? this.form.controls.busyTimeoutMs.value,
        host: configuration.host ?? this.form.controls.host.value,
        port: configuration.port ?? (type === 'POSTGRESQL' ? 5432 : 3306),
        databaseName: configuration.databaseName ?? this.form.controls.databaseName.value,
        username: configuration.username ?? this.form.controls.username.value,
        password: '',
        sslMode: configuration.sslMode ?? 'PREFERRED',
        connectTimeoutMs:
          configuration.connectTimeoutMs ?? this.form.controls.connectTimeoutMs.value
      },
      { emitEvent: false }
    );
    this.form.markAsPristine();
    this.form.markAsUntouched();
    this.formVersion.update((version) => version + 1);
    this.testedFormVersion.set(null);
    this.pendingAction.set(null);
  }

  private configureValidators(type: DatabaseType): void {
    const sqlite = type === 'SQLITE';
    this.form.controls.sqlitePath.setValidators(
      sqlite
        ? [Validators.required, Validators.maxLength(4096), trimmedText]
        : [Validators.maxLength(4096), trimmedText]
    );
    this.form.controls.busyTimeoutMs.setValidators(
      sqlite
        ? [Validators.required, Validators.min(100), Validators.max(60000), safeInteger]
        : [Validators.min(100), Validators.max(60000), safeInteger]
    );
    const serverText = [Validators.required, Validators.maxLength(128), trimmedText];
    this.form.controls.host.setValidators(
      sqlite
        ? [Validators.maxLength(253), trimmedText]
        : [
            Validators.required,
            Validators.maxLength(253),
            trimmedText,
            Validators.pattern(/^[^\s/?#@&=\\]+$/)
          ]
    );
    this.form.controls.databaseName.setValidators(sqlite ? [Validators.maxLength(128)] : serverText);
    this.form.controls.username.setValidators(sqlite ? [Validators.maxLength(128)] : serverText);
    this.form.controls.port.setValidators(
      sqlite
        ? [Validators.min(1), Validators.max(65535), safeInteger]
        : [Validators.required, Validators.min(1), Validators.max(65535), safeInteger]
    );
    this.form.controls.connectTimeoutMs.setValidators(
      sqlite
        ? [Validators.min(500), Validators.max(60000), safeInteger]
        : [Validators.required, Validators.min(500), Validators.max(60000), safeInteger]
    );
    for (const control of [
      this.form.controls.sqlitePath,
      this.form.controls.busyTimeoutMs,
      this.form.controls.host,
      this.form.controls.port,
      this.form.controls.databaseName,
      this.form.controls.username,
      this.form.controls.connectTimeoutMs
    ]) {
      control.updateValueAndValidity({ emitEvent: false });
    }
  }

  private buildCandidate(): DatabaseCandidate | null {
    this.form.markAllAsTouched();
    const passwordControl = this.form.controls.password;
    if (this.passwordRequired() && passwordControl.value.length === 0) {
      passwordControl.setErrors({ ...(passwordControl.errors ?? {}), required: true });
    } else if (!this.passwordRequired() && passwordControl.hasError('required')) {
      const remainingErrors = { ...(passwordControl.errors ?? {}) };
      delete remainingErrors['required'];
      passwordControl.setErrors(Object.keys(remainingErrors).length > 0 ? remainingErrors : null);
    }
    if (this.form.invalid) {
      this.actionError.set('请修正数据库配置后重试。');
      return null;
    }

    const value = this.form.getRawValue();
    if (value.type === 'SQLITE') {
      return {
        type: value.type,
        sqlitePath: value.sqlitePath.trim(),
        busyTimeoutMs: value.busyTimeoutMs
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
      connectTimeoutMs: value.connectTimeoutMs
    };
  }

  private handleMutationError(error: unknown, fallback: string): void {
    this.actionError.set(this.errorMessage(error, fallback));
    if (
      error instanceof HttpErrorResponse &&
      error.status === 409 &&
      error.error?.code === 'DATABASE_CONFIG_CONFLICT'
    ) {
      this.loadConfiguration(true);
    }
  }

  private handleFileMutationError(error: unknown): void {
    this.fileError.set(this.errorMessage(error, '候选配置加载失败。'));
    if (
      error instanceof HttpErrorResponse &&
      error.status === 409 &&
      error.error?.code === 'DATABASE_CONFIG_CONFLICT'
    ) {
      this.loadConfiguration(true);
    }
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof HttpErrorResponse)) {
      return fallback;
    }
    if (error.status === 0) {
      return '无法连接管理服务。';
    }
    const code = typeof error.error?.code === 'string' ? error.error.code : '';
    const messages: Record<string, string> = {
      DATABASE_CONNECTION_FAILED: '无法连接目标数据库，请检查地址、端口、账号和 TLS 设置。',
      DATABASE_READ_FAILED: '目标数据库读取检查失败。',
      DATABASE_WRITE_FAILED: '目标数据库写入检查失败，请确认账号权限。',
      TARGET_SCHEMA_INCOMPATIBLE: '目标数据库 schema 与当前版本不兼容。',
      TARGET_ADMIN_MISSING: '目标库已有数据但缺少当前管理员，已拒绝切换。',
      CURRENT_ADMIN_MISSING: '当前管理员记录不可用，已拒绝切换。',
      DATABASE_CONFIG_CONFLICT: '数据库配置已被其他操作更新，页面已重新载入。',
      DATABASE_SWITCH_IN_PROGRESS: '已有数据库切换正在执行。',
      DATABASE_CONFIG_INVALID: '候选配置文件不存在、格式错误或密码文件不可读。',
      APP_SECRET_KEY_UNAVAILABLE: '主密钥未配置，无法安全保存数据库密码。',
      VALIDATION_FAILED: '数据库配置校验失败。'
    };
    return messages[code] ?? fallback;
  }
}
