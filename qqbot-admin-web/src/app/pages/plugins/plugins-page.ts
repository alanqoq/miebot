import { DatePipe } from '@angular/common';
import { HttpErrorResponse, HttpEvent, HttpEventType } from '@angular/common/http';
import { Component, DestroyRef, HostListener, OnInit, computed, inject, signal } from '@angular/core';
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
  LucideCircleAlert,
  LucidePlus,
  LucidePuzzle,
  LucideRefreshCw,
  LucideSave,
  LucideSearch,
  LucideSettings,
  LucideShieldCheck,
  LucideTrash2,
  LucideUpload,
  LucideX,
} from '@lucide/angular';
import { forkJoin, of } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { ApiErrorResponse, BotApiService, BotConfiguration } from '../../core/bot-api.service';
import {
  EventApiService,
  PluginDeliveryPage,
  PluginDeliveryStatus,
  PluginDeliverySummary,
} from '../../core/event-api.service';
import {
  CreatePluginBindingRequest,
  PluginApiService,
  PluginArtifact,
  PluginBinding,
  PluginInventory,
  PluginUploadResponse,
  MAX_PLUGIN_UPLOAD_BYTES,
  UpdatePluginBindingRequest,
} from '../../core/plugin-api.service';

type BindingDialogMode = 'create' | 'edit';

const jsonObject: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  if (typeof control.value !== 'string' || !control.value.trim()) return { jsonObject: true };
  try {
    const parsed: unknown = JSON.parse(control.value);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? null
      : { jsonObject: true };
  } catch {
    return { jsonObject: true };
  }
};

@Component({
  selector: 'app-plugins-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    LucideCircleAlert,
    LucidePlus,
    LucidePuzzle,
    LucideRefreshCw,
    LucideSave,
    LucideSearch,
    LucideSettings,
    LucideShieldCheck,
    LucideTrash2,
    LucideUpload,
    LucideX,
  ],
  templateUrl: './plugins-page.html',
  styleUrl: './plugins-page.scss',
})
export class PluginsPage implements OnInit {
  private readonly api = inject(PluginApiService);
  private readonly botApi = inject(BotApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly inventory = signal<PluginInventory | null>(null);
  protected readonly items = signal<PluginArtifact[]>([]);
  protected readonly bindings = signal<PluginBinding[]>([]);
  protected readonly bots = signal<BotConfiguration[]>([]);
  protected readonly deliveries = signal<PluginDeliverySummary[]>([]);
  protected readonly deliveryPage = signal<PluginDeliveryPage | null>(null);
  protected readonly deliveryWarning = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly loading = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly reloading = signal(false);
  protected readonly loaded = signal(false);
  protected readonly pageError = signal<string | null>(null);
  protected readonly warning = signal<string | null>(null);
  protected readonly dialogMode = signal<BindingDialogMode | null>(null);
  protected readonly editingBinding = signal<PluginBinding | null>(null);
  protected readonly saving = signal(false);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly mutatingIds = signal<ReadonlySet<string>>(new Set());
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly uploadTrusted = signal(false);
  protected readonly uploading = signal(false);
  protected readonly uploadProgress = signal<number | null>(null);
  protected readonly uploadError = signal<string | null>(null);
  protected readonly uploadResult = signal<PluginUploadResponse | null>(null);

  protected readonly discoveredCount = computed(() => this.items().length);
  protected readonly loadedCount = computed(() => this.items().filter((item) => item.loaded).length);
  protected readonly enabledBindingCount = computed(() =>
    this.bindings().filter((binding) => binding.enabled).length,
  );

  protected readonly bindingForm = this.formBuilder.group({
    pluginId: ['', Validators.required],
    botId: ['', Validators.required],
    configJson: ['{}', [Validators.required, Validators.maxLength(65536), jsonObject]],
    enabled: [true],
  });

  ngOnInit(): void {
    this.loadAll();
  }

  protected loadAll(): void {
    this.loading.set(true);
    this.pageError.set(null);
    forkJoin({
      inventory: this.api.list(this.search()),
      bindings: this.api.listBindings(),
      bots: this.botApi.list(),
      deliveries: this.eventApi.listPluginDeliveries({ limit: 25 }).pipe(catchError(() => of(null))),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ inventory, bindings, bots, deliveries }) => {
          this.applyInventory(inventory);
          this.bindings.set(this.sortBindings(bindings));
          this.bots.set(bots);
          this.applyDeliveries(deliveries);
          this.loaded.set(true);
        },
        error: (error: unknown) => {
          this.pageError.set(this.errorMessage(error, '无法读取插件和绑定，请稍后重试。'));
        },
      });
  }

  protected updateSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  protected handleSearchKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this.scan();
    }
  }

  protected scan(force = false): void {
    if (this.loading() || this.refreshing() || this.reloading() || (!force && this.uploading())) return;
    this.refreshing.set(true);
    this.warning.set(null);
    this.api
      .list(this.search())
      .pipe(
        finalize(() => this.refreshing.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (inventory) => this.applyInventory(inventory),
        error: (error: unknown) => {
          this.warning.set(this.errorMessage(error, '无法读取插件目录，请稍后重试。'));
        },
      });
  }

  protected reloadHost(): void {
    if (this.loading() || this.refreshing() || this.reloading() || this.uploading()) return;
    this.reloading.set(true);
    this.warning.set(null);
    forkJoin({ inventory: this.api.reload(this.search()), bindings: this.api.listBindings() })
      .pipe(
        finalize(() => this.reloading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ inventory, bindings }) => {
          this.applyInventory(inventory);
          this.bindings.set(this.sortBindings(bindings));
        },
        error: (error: unknown) => {
          this.warning.set(this.errorMessage(error, '插件宿主重新加载失败。'));
        },
      });
  }

  protected openUploadPicker(input: HTMLInputElement): void {
    if (this.loading() || this.refreshing() || this.reloading() || this.uploading()) return;
    input.click();
  }

  protected selectPluginFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) return;
    this.selectedFile.set(null);
    this.uploadResult.set(null);
    this.uploadError.set(null);
    this.uploadProgress.set(null);
    this.uploadTrusted.set(false);
    const lowerName = file.name.toLowerCase();
    if (!lowerName.endsWith('.jar')) {
      this.uploadError.set('只允许选择 .jar 插件制品。');
      return;
    }
    if (file.size < 1 || file.size > MAX_PLUGIN_UPLOAD_BYTES) {
      this.uploadError.set('插件 JAR 不能超过 64 MiB，且不能为空。');
      return;
    }
    this.selectedFile.set(file);
  }

  protected closeUpload(): void {
    if (this.uploading()) return;
    this.selectedFile.set(null);
    this.uploadTrusted.set(false);
    this.uploadProgress.set(null);
    this.uploadError.set(null);
  }

  protected setUploadTrusted(event: Event): void {
    this.uploadTrusted.set((event.target as HTMLInputElement).checked);
  }

  protected uploadSelected(): void {
    const file = this.selectedFile();
    if (!file || !this.uploadTrusted() || this.loading() || this.refreshing()
        || this.reloading() || this.uploading()) return;
    this.uploading.set(true);
    this.uploadError.set(null);
    this.uploadProgress.set(0);
    this.api
      .upload(file)
      .pipe(
        finalize(() => this.uploading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (event: HttpEvent<PluginUploadResponse>) => {
          if (event.type === HttpEventType.UploadProgress) {
            const total = event.total ?? file.size;
            this.uploadProgress.set(total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : null);
          } else if (event.type === HttpEventType.Response && event.body) {
            this.uploadProgress.set(100);
            this.uploadResult.set(event.body);
            this.scan(true);
          }
        },
        error: (error: unknown) => {
          this.uploadError.set(this.errorMessage(error, '插件上传或热升级失败。'));
        },
      });
  }

  protected uploadOperationLabel(operation: string): string {
    switch (operation) {
      case 'INSTALLED': return '已安装';
      case 'UPGRADED': return '已热升级';
      case 'UNCHANGED': return '版本未变化';
      default: return operation || '已完成';
    }
  }

  protected openCreate(plugin: PluginArtifact): void {
    this.dialogMode.set('create');
    this.editingBinding.set(null);
    this.dialogError.set(null);
    const bot = this.availableBots(plugin.id)[0];
    this.bindingForm.reset({
      pluginId: plugin.id,
      botId: bot?.id ?? '',
      configJson: '{}',
      enabled: true,
    });
    this.bindingForm.controls.pluginId.disable();
  }

  protected openEdit(binding: PluginBinding): void {
    this.dialogMode.set('edit');
    this.editingBinding.set(binding);
    this.dialogError.set(null);
    this.bindingForm.reset({
      pluginId: binding.pluginId,
      botId: binding.botId,
      configJson: this.prettyJson(binding.configJson),
      enabled: binding.enabled,
    });
    this.bindingForm.controls.pluginId.disable();
    this.bindingForm.controls.botId.disable();
  }

  protected closeDialog(): void {
    if (this.saving()) return;
    this.dialogMode.set(null);
    this.editingBinding.set(null);
    this.bindingForm.controls.pluginId.enable();
    this.bindingForm.controls.botId.enable();
  }

  protected saveBinding(): void {
    if (this.bindingForm.invalid || this.saving()) {
      this.bindingForm.markAllAsTouched();
      return;
    }
    const raw = this.bindingForm.getRawValue();
    this.saving.set(true);
    this.dialogError.set(null);
    const editing = this.editingBinding();
    const request = editing
      ? this.api.updateBinding(editing.id, {
          expectedRevision: editing.revision,
          configJson: raw.configJson,
          enabled: raw.enabled,
        })
      : this.api.createBinding({
          pluginId: raw.pluginId,
          botId: raw.botId,
          configJson: raw.configJson,
          enabled: raw.enabled,
        });
    request
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (binding) => {
          this.replaceBinding(binding);
          this.closeDialog();
          this.scan();
        },
        error: (error: unknown) => {
          this.dialogError.set(this.errorMessage(error, '保存插件绑定失败。'));
        },
      });
  }

  protected toggleBinding(binding: PluginBinding): void {
    if (this.isMutating(binding.id)) return;
    this.setMutating(binding.id, true);
    const request: UpdatePluginBindingRequest = {
      expectedRevision: binding.revision,
      configJson: binding.configJson,
      enabled: !binding.enabled,
    };
    this.api
      .updateBinding(binding.id, request)
      .pipe(
        finalize(() => this.setMutating(binding.id, false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (saved) => {
          this.replaceBinding(saved);
          this.scan();
        },
        error: (error: unknown) => {
          this.warning.set(this.errorMessage(error, '更新插件绑定失败。'));
        },
      });
  }

  protected deleteBinding(binding: PluginBinding): void {
    if (this.isMutating(binding.id) || !window.confirm('确认删除这个机器人插件绑定？')) return;
    this.setMutating(binding.id, true);
    this.api
      .deleteBinding(binding.id)
      .pipe(
        finalize(() => this.setMutating(binding.id, false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.bindings.update((items) => items.filter((item) => item.id !== binding.id));
          this.scan();
        },
        error: (error: unknown) => {
          this.warning.set(this.errorMessage(error, '删除插件绑定失败。'));
        },
      });
  }

  protected resetBinding(binding: PluginBinding): void {
    if (this.isMutating(binding.id)) return;
    this.setMutating(binding.id, true);
    this.api.resetBinding(binding.id)
      .pipe(finalize(() => this.setMutating(binding.id, false)), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => this.replaceBinding(saved),
        error: (error: unknown) => this.warning.set(this.errorMessage(error, '恢复插件绑定失败。')),
      });
  }

  protected bindingRuntimeLabel(binding: PluginBinding): string {
    switch (binding.runtimeState) {
      case 'QUARANTINED': return '已隔离';
      case 'PAUSED': return '已暂停';
      default: return binding.enabled ? '运行中' : '已停用';
    }
  }

  protected bindingRuntimeClass(binding: PluginBinding): string {
    if (binding.runtimeState === 'QUARANTINED') return 'delivery-failed';
    if (binding.runtimeState === 'PAUSED' || !binding.enabled) return 'delivery-pending';
    return 'delivery-success';
  }

  protected bindingsFor(pluginId: string): PluginBinding[] {
    return this.bindings().filter((binding) => binding.pluginId === pluginId);
  }

  protected availableBots(pluginId: string): BotConfiguration[] {
    const bound = new Set(this.bindingsFor(pluginId).map((binding) => binding.botId));
    return this.bots().filter((bot) => !bound.has(bot.id));
  }

  protected botName(botId: string): string {
    const bot = this.bots().find((item) => item.id === botId);
    return bot ? `${bot.displayName} (${bot.environment === 'PRODUCTION' ? '正式' : '沙箱'})` : botId;
  }

  protected pluginName(pluginId: string): string {
    return this.items().find((item) => item.id === pluginId)?.name || pluginId;
  }

  protected isMutating(id: string): boolean {
    return this.mutatingIds().has(id);
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'LOADED': return '已加载';
      case 'DISCOVERED': return '待加载';
      case 'INVALID': return '无效制品';
      case 'UNSUPPORTED': return '未声明入口';
      default: return status || '未知';
    }
  }

  protected statusClass(status: string): string {
    if (status === 'LOADED') return 'plugin-status-loaded';
    if (status === 'DISCOVERED') return 'plugin-status-discovered';
    if (status === 'INVALID') return 'plugin-status-invalid';
    return 'plugin-status-unsupported';
  }

  protected deliveryStatusLabel(status: PluginDeliveryStatus): string {
    const labels: Record<PluginDeliveryStatus, string> = {
      PENDING: '待投递',
      IN_PROGRESS: '执行中',
      RETRY_WAIT: '等待重试',
      SUCCEEDED: '已完成',
      DEAD_LETTER: '死信',
      PAUSED: '已暂停',
    };
    return labels[status];
  }

  protected deliveryStatusClass(status: PluginDeliveryStatus): string {
    if (status === 'SUCCEEDED') return 'delivery-success';
    if (status === 'DEAD_LETTER') return 'delivery-failed';
    if (status === 'IN_PROGRESS' || status === 'RETRY_WAIT') return 'delivery-pending';
    return 'delivery-idle';
  }

  protected refreshDeliveries(): void {
    this.deliveryWarning.set(null);
    this.eventApi.listPluginDeliveries({ limit: 25 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.applyDeliveries(page),
        error: (error: unknown) => this.deliveryWarning.set(
          this.errorMessage(error, '无法读取插件投递记录。'),
        ),
      });
  }

  protected formatBytes(value: number): string {
    if (!Number.isFinite(value) || value < 0) return '--';
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
    return `${(value / (1024 * 1024)).toFixed(1)} MiB`;
  }

  protected shortHash(hash: string): string {
    return hash.length > 16 ? `${hash.slice(0, 8)}...${hash.slice(-8)}` : hash || '--';
  }

  protected inventoryMessage(): string {
    const value = this.inventory();
    if (!value) return '等待扫描结果';
    if (!value.directoryExists) return `插件目录不存在：${value.directory}`;
    return `扫描目录：${value.directory}`;
  }

  protected emptyMessage(): string {
    if (this.search().trim()) return '没有匹配的插件制品';
    if (this.inventory() && !this.inventory()!.directoryExists) return '尚未挂载插件目录';
    return '未发现插件制品';
  }

  protected configError(): string | null {
    const control = this.bindingForm.controls.configJson;
    if (!control.touched || !control.errors) return null;
    if (control.hasError('maxlength')) return '配置不能超过 64 KiB';
    return '配置必须是有效的 JSON 对象';
  }

  @HostListener('document:keydown.escape')
  protected handleEscape(): void {
    if (this.dialogMode()) this.closeDialog();
  }

  private applyInventory(inventory: PluginInventory): void {
    this.inventory.set(inventory);
    this.items.set(inventory.items);
    this.loaded.set(true);
    this.pageError.set(null);
    this.warning.set(inventory.scanError);
  }

  private applyDeliveries(page: PluginDeliveryPage | null): void {
    this.deliveryPage.set(page);
    this.deliveries.set(page?.items ?? []);
    this.deliveryWarning.set(page ? null : '插件投递记录暂不可用。');
  }

  private replaceBinding(binding: PluginBinding): void {
    this.bindings.update((items) =>
      this.sortBindings([...items.filter((item) => item.id !== binding.id), binding]),
    );
  }

  private sortBindings(items: PluginBinding[]): PluginBinding[] {
    return [...items].sort(
      (left, right) => left.pluginId.localeCompare(right.pluginId) || left.createdAt.localeCompare(right.createdAt),
    );
  }

  private setMutating(id: string, active: boolean): void {
    this.mutatingIds.update((current) => {
      const next = new Set(current);
      if (active) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  private prettyJson(value: string): string {
    try { return JSON.stringify(JSON.parse(value), null, 2); }
    catch { return value; }
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
      const response = error.error as Partial<ApiErrorResponse>;
      if (typeof response.message === 'string') return response.message;
    }
    return fallback;
  }
}
