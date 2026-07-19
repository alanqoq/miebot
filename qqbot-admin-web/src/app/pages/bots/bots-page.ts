import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  HostListener,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import {
  LucideActivity,
  LucideBot,
  LucidePlus,
  LucideRefreshCw,
  LucideSearch,
  LucideSettings,
  LucideTrash2,
  LucideX,
} from '@lucide/angular';
import { EMPTY, timer } from 'rxjs';
import { catchError, exhaustMap, finalize } from 'rxjs/operators';
import {
  ApiErrorResponse,
  BotApiService,
  BotConfiguration,
  BotEnvironment,
  BotRuntimeState,
  BotRuntimeStatus,
  BotRuntimeSummary,
  CreateBotRequest,
  UpdateBotRequest,
} from '../../core/bot-api.service';
import {
  DEFAULT_GATEWAY_INTENTS,
  GATEWAY_INTENT_OPTIONS,
  hasGatewayIntent,
  setGatewayIntent,
  unknownGatewayIntents,
} from '../../core/gateway-intents';

type DialogMode = 'create' | 'edit';

interface BotFormValue {
  displayName: string;
  appId: string;
  environment: BotEnvironment;
  intents: number;
  shardIndex: number;
  shardCount: number;
  enabled: boolean;
  appSecret: string;
}

const trimmedText: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  return typeof value === 'string' && value.length > 0 && value !== value.trim()
    ? { surroundingWhitespace: true }
    : null;
};

const tokenText: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  return typeof value === 'string' && /\s/.test(value) ? { whitespace: true } : null;
};

const safeInteger: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value;
  return typeof value === 'number' && Number.isSafeInteger(value) ? null : { integer: true };
};

const shardRange: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const index = control.get('shardIndex')?.value;
  const count = control.get('shardCount')?.value;
  return typeof index === 'number' && typeof count === 'number' && index >= count
    ? { shardRange: true }
    : null;
};

@Component({
  selector: 'app-bots-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    LucideActivity,
    LucideBot,
    LucidePlus,
    LucideRefreshCw,
    LucideSearch,
    LucideSettings,
    LucideTrash2,
    LucideX,
  ],
  templateUrl: './bots-page.html',
  styleUrl: './bots-page.scss',
})
export class BotsPage implements OnInit {
  private readonly api = inject(BotApiService);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly bots = signal<BotConfiguration[]>([]);
  protected readonly runtimeStatuses = signal<ReadonlyMap<string, BotRuntimeStatus>>(new Map());
  protected readonly runtimeUnavailable = signal(false);
  protected readonly loading = signal(true);
  protected readonly pageError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly dialogMode = signal<DialogMode | null>(null);
  protected readonly dialogLoading = signal(false);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly revisionConflict = signal(false);
  protected readonly editingId = signal<string | null>(null);
  protected readonly editingBot = signal<BotConfiguration | null>(null);
  protected readonly saving = signal(false);
  protected readonly mutatingIds = signal<ReadonlySet<string>>(new Set());
  protected readonly intentOptions = GATEWAY_INTENT_OPTIONS;

  protected readonly filteredBots = computed(() => {
    const query = this.search().trim().toLocaleLowerCase();
    if (!query) {
      return this.bots();
    }
    return this.bots().filter((bot) =>
      [bot.displayName, bot.appId, bot.environment].some((value) =>
        value.toLocaleLowerCase().includes(query),
      ),
    );
  });

  protected readonly form = this.formBuilder.group(
    {
      displayName: ['', [Validators.required, Validators.maxLength(128), trimmedText]],
      appId: ['', [Validators.required, Validators.maxLength(128), tokenText]],
      environment: ['SANDBOX' as BotEnvironment, Validators.required],
      intents: [DEFAULT_GATEWAY_INTENTS, [Validators.required, Validators.min(0), safeInteger]],
      shardIndex: [0, [Validators.required, Validators.min(0), Validators.max(4095), safeInteger]],
      shardCount: [1, [Validators.required, Validators.min(1), Validators.max(4096), safeInteger]],
      enabled: [true],
      appSecret: ['', [Validators.maxLength(4096)]],
    },
    { validators: shardRange },
  );

  ngOnInit(): void {
    this.loadBots();
    this.api.observeRuntime()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((summary) => this.applyRuntimeSummary(summary));
    timer(5000, 5000)
      .pipe(
        exhaustMap(() =>
          this.api.getRuntimeSummary().pipe(
            catchError(() => {
              this.markRuntimeUnavailable();
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((summary) => this.applyRuntimeSummary(summary));
  }

  protected loadBots(): void {
    this.loading.set(true);
    this.pageError.set(null);
    this.refreshRuntime();
    this.api
      .list()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (bots) => this.bots.set(this.sortBots(bots)),
        error: (error: unknown) => {
          this.pageError.set(this.errorMessage(error, '无法加载机器人列表，请稍后重试。'));
        },
      });
  }

  protected updateSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  protected openCreate(): void {
    this.dialogMode.set('create');
    this.dialogLoading.set(false);
    this.dialogError.set(null);
    this.revisionConflict.set(false);
    this.editingId.set(null);
    this.editingBot.set(null);
    this.configureSecretValidation(true);
    this.form.reset({
      displayName: '',
      appId: '',
      environment: 'SANDBOX',
      intents: DEFAULT_GATEWAY_INTENTS,
      shardIndex: 0,
      shardCount: 1,
      enabled: true,
      appSecret: '',
    });
  }

  protected openEdit(bot: BotConfiguration): void {
    this.dialogMode.set('edit');
    this.dialogError.set(null);
    this.revisionConflict.set(false);
    this.editingId.set(bot.id);
    this.editingBot.set(null);
    this.configureSecretValidation(false);
    this.loadEditingBot();
  }

  protected loadEditingBot(): void {
    const id = this.editingId();
    if (!id) {
      return;
    }
    this.dialogLoading.set(true);
    this.dialogError.set(null);
    this.revisionConflict.set(false);
    this.api
      .get(id)
      .pipe(finalize(() => this.dialogLoading.set(false)))
      .subscribe({
        next: (bot) => {
          this.editingBot.set(bot);
          this.replaceBot(bot);
          this.form.reset({
            displayName: bot.displayName,
            appId: bot.appId,
            environment: bot.environment,
            intents: bot.intents,
            shardIndex: bot.shardIndex,
            shardCount: bot.shardCount,
            enabled: bot.enabled,
            appSecret: '',
          });
        },
        error: (error: unknown) => {
          this.editingBot.set(null);
          this.dialogError.set(this.errorMessage(error, '无法加载机器人详情。'));
        },
      });
  }

  protected closeDialog(): void {
    if (this.saving()) {
      return;
    }
    this.dialogMode.set(null);
    this.dialogError.set(null);
    this.revisionConflict.set(false);
    this.editingId.set(null);
    this.editingBot.set(null);
    this.form.reset();
  }

  protected save(): void {
    this.dialogError.set(null);
    this.revisionConflict.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const mode = this.dialogMode();
    if (!mode) {
      return;
    }

    this.saving.set(true);
    this.form.disable();
    const request$ =
      mode === 'create'
        ? this.api.create({
            displayName: value.displayName,
            appId: value.appId,
            environment: value.environment,
            intents: value.intents,
            shardIndex: value.shardIndex,
            shardCount: value.shardCount,
            enabled: value.enabled,
            appSecret: value.appSecret,
          } satisfies CreateBotRequest)
        : this.updateRequest(value);

    request$
      .pipe(
        finalize(() => {
          this.saving.set(false);
          this.form.enable();
        }),
      )
      .subscribe({
        next: (bot) => {
          this.replaceBot(bot);
          this.refreshRuntime();
          this.saving.set(false);
          this.closeDialog();
        },
        error: (error: unknown) => this.handleSaveError(error),
      });
  }

  protected toggleEnabled(bot: BotConfiguration): void {
    if (this.isMutating(bot.id)) {
      return;
    }
    this.actionError.set(null);
    this.setMutating(bot.id, true);
    this.api
      .setEnabled(bot.id, {
        expectedRevision: bot.revision,
        enabled: !bot.enabled,
      })
      .pipe(finalize(() => this.setMutating(bot.id, false)))
      .subscribe({
        next: (updated) => {
          this.replaceBot(updated);
          this.refreshRuntime();
        },
        error: (error: unknown) => {
          if (this.isRevisionConflict(error)) {
            this.actionError.set('机器人配置已被更新，列表已重新载入，请再次操作。');
            this.loadBots();
            return;
          }
          this.actionError.set(this.errorMessage(error, '启停操作失败，请稍后重试。'));
        },
      });
  }

  protected deleteBot(bot: BotConfiguration): void {
    if (this.isMutating(bot.id) || !window.confirm(
      `确认删除机器人“${bot.displayName}”？该机器人关联的 Inbox、Outbox、插件绑定和投递记录都会永久删除。`,
    )) {
      return;
    }
    this.actionError.set(null);
    this.setMutating(bot.id, true);
    this.api
      .delete(bot.id)
      .pipe(finalize(() => this.setMutating(bot.id, false)))
      .subscribe({
        next: () => {
          this.bots.update((items) => items.filter((item) => item.id !== bot.id));
          this.runtimeStatuses.update((statuses) => {
            const next = new Map(statuses);
            next.delete(bot.id);
            return next;
          });
        },
        error: (error: unknown) => {
          this.actionError.set(this.errorMessage(error, '删除机器人失败，请稍后重试。'));
        },
      });
  }

  protected isMutating(id: string): boolean {
    return this.mutatingIds().has(id);
  }

  protected runtimeFor(bot: BotConfiguration): BotRuntimeStatus | null {
    return this.runtimeStatuses().get(bot.id) ?? null;
  }

  protected runtimeStateLabel(state: BotRuntimeState): string {
    const labels: Record<BotRuntimeState, string> = {
      DISABLED: '未运行',
      STARTING: '启动中',
      DISCOVERING: '获取接入点',
      CONNECTING: '连接中',
      AUTHENTICATING: '认证中',
      READY: '在线',
      ONLINE: '在线',
      RECONNECTING: '重连中',
      STOPPING: '停止中',
      FAILED: '异常',
      STOPPED: '已停止',
    };
    return labels[state];
  }

  protected runtimeStateClass(state: BotRuntimeState): string {
    if (state === 'READY' || state === 'ONLINE') {
      return 'runtime-ready';
    }
    if (state === 'FAILED') {
      return 'runtime-failed';
    }
    if (
      state === 'STARTING' ||
      state === 'DISCOVERING' ||
      state === 'CONNECTING' ||
      state === 'AUTHENTICATING' ||
      state === 'RECONNECTING' ||
      state === 'STOPPING'
    ) {
      return 'runtime-pending';
    }
    return 'runtime-idle';
  }

  protected shortSessionId(sessionId: string): string {
    if (sessionId.length <= 14) {
      return sessionId;
    }
    return `${sessionId.slice(0, 6)}...${sessionId.slice(-6)}`;
  }

  protected hasIntent(bit: number): boolean {
    return hasGatewayIntent(this.form.controls.intents.value, bit);
  }

  protected toggleIntent(bit: number, event: Event): void {
    const selected = (event.target as HTMLInputElement).checked;
    const control = this.form.controls.intents;
    control.setValue(setGatewayIntent(control.value, bit, selected));
    control.markAsDirty();
    control.markAsTouched();
  }

  protected unknownIntentBits(): number {
    return unknownGatewayIntents(this.form.controls.intents.value);
  }

  protected fieldError(control: AbstractControl, label: string): string | null {
    if (!control.touched || !control.errors) {
      return null;
    }
    if (typeof control.errors['server'] === 'string') {
      return control.errors['server'];
    }
    if (control.hasError('required')) {
      return `${label}不能为空`;
    }
    if (control.hasError('maxlength')) {
      return `${label}长度超出限制`;
    }
    if (control.hasError('min')) {
      return `${label}小于允许值`;
    }
    if (control.hasError('max')) {
      return `${label}大于允许值`;
    }
    if (control.hasError('integer')) {
      return `${label}必须是安全整数`;
    }
    if (control.hasError('whitespace')) {
      return `${label}不能包含空白字符`;
    }
    if (control.hasError('surroundingWhitespace')) {
      return `${label}首尾不能有空格`;
    }
    return `${label}格式不正确`;
  }

  @HostListener('document:keydown.escape')
  protected handleEscape(): void {
    if (this.dialogMode()) {
      this.closeDialog();
    }
  }

  private updateRequest(value: BotFormValue) {
    const bot = this.editingBot();
    if (!bot) {
      throw new Error('Cannot update a bot before its details are loaded');
    }
    const request: UpdateBotRequest = {
      expectedRevision: bot.revision,
      displayName: value.displayName,
      appId: value.appId,
      environment: value.environment,
      intents: value.intents,
      shardIndex: value.shardIndex,
      shardCount: value.shardCount,
      ...(value.appSecret ? { appSecret: value.appSecret } : {}),
    };
    return this.api.update(bot.id, request);
  }

  private handleSaveError(error: unknown): void {
    this.applyFieldErrors(error);
    if (this.isRevisionConflict(error)) {
      this.revisionConflict.set(true);
      this.dialogError.set('配置版本已变化，当前内容尚未保存。');
      return;
    }
    this.dialogError.set(this.errorMessage(error, '保存失败，请检查输入后重试。'));
  }

  private applyFieldErrors(error: unknown): void {
    const response = this.apiError(error);
    if (!response?.fieldErrors) {
      return;
    }
    for (const [field, message] of Object.entries(response.fieldErrors)) {
      const control = this.form.get(field);
      if (control) {
        control.setErrors({ ...control.errors, server: message });
        control.markAsTouched();
      }
    }
  }

  private configureSecretValidation(required: boolean): void {
    const validators = required
      ? [Validators.required, Validators.maxLength(4096)]
      : [Validators.maxLength(4096)];
    this.form.controls.appSecret.setValidators(validators);
    this.form.controls.appSecret.updateValueAndValidity();
  }

  private replaceBot(bot: BotConfiguration): void {
    this.bots.update((bots) => this.sortBots([...bots.filter((item) => item.id !== bot.id), bot]));
  }

  private refreshRuntime(): void {
    this.api
      .getRuntimeSummary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (summary) => this.applyRuntimeSummary(summary),
        error: () => this.markRuntimeUnavailable(),
      });
  }

  private applyRuntimeSummary(summary: BotRuntimeSummary): void {
    this.runtimeStatuses.set(new Map(summary.bots.map((status) => [status.botId, status])));
    this.runtimeUnavailable.set(false);
  }

  private markRuntimeUnavailable(): void {
    this.runtimeStatuses.set(new Map());
    this.runtimeUnavailable.set(true);
  }

  private sortBots(bots: BotConfiguration[]): BotConfiguration[] {
    return [...bots].sort(
      (left, right) =>
        left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id),
    );
  }

  private setMutating(id: string, active: boolean): void {
    this.mutatingIds.update((current) => {
      const next = new Set(current);
      if (active) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  private isRevisionConflict(error: unknown): boolean {
    const response = this.apiError(error);
    return (
      error instanceof HttpErrorResponse &&
      error.status === 409 &&
      response?.code === 'REVISION_CONFLICT'
    );
  }

  private errorMessage(error: unknown, fallback: string): string {
    return this.apiError(error)?.message || fallback;
  }

  private apiError(error: unknown): ApiErrorResponse | null {
    if (!(error instanceof HttpErrorResponse) || !error.error || typeof error.error !== 'object') {
      return null;
    }
    const response = error.error as Partial<ApiErrorResponse>;
    if (typeof response.code !== 'string' || typeof response.message !== 'string') {
      return null;
    }
    return response as ApiErrorResponse;
  }
}
