import { DatePipe } from '@angular/common';
import { HttpErrorResponse, HttpEvent, HttpEventType } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnInit,
  ViewChild,
  computed,
  inject,
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
  LucideArrowLeft,
  LucideBot,
  LucideChevronRight,
  LucideCircleAlert,
  LucideDownload,
  LucideFile,
  LucideFileJson,
  LucideFilePlus2,
  LucideFolder,
  LucideFolderPlus,
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
import { EMPTY, forkJoin, of, timer } from 'rxjs';
import { catchError, exhaustMap, finalize } from 'rxjs/operators';
import {
  ApiErrorResponse,
  BotApiService,
  BotConfiguration,
  BotRuntimeStatus,
  BotRuntimeSummary,
} from '../../core/bot-api.service';
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
  PluginFileEntry,
  PluginTextFile,
  PluginInventory,
  PluginUploadResponse,
  MAX_PLUGIN_UPLOAD_BYTES,
} from '../../core/plugin-api.service';

interface BindingFileState {
  path: string;
  entries: PluginFileEntry[];
  loading: boolean;
  error: string | null;
}

interface BindingEditorState extends PluginTextFile {
  saving: boolean;
  error: string | null;
}

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

const maxUtf8Bytes = (maximum: number): ValidatorFn =>
  (control: AbstractControl): ValidationErrors | null => {
    if (typeof control.value !== 'string') return null;
    const actual = new TextEncoder().encode(control.value).byteLength;
    return actual > maximum ? { maxUtf8Bytes: { maximum, actual } } : null;
  };

@Component({
  selector: 'app-plugins-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    LucideArrowLeft,
    LucideBot,
    LucideChevronRight,
    LucideCircleAlert,
    LucideDownload,
    LucideFile,
    LucideFileJson,
    LucideFilePlus2,
    LucideFolder,
    LucideFolderPlus,
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
  styleUrls: ['./plugins-page.scss', './plugins-file-manager.scss'],
})
export class PluginsPage implements OnInit {
  private readonly api = inject(PluginApiService);
  private readonly botApi = inject(BotApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private bindingDialogTrigger: HTMLElement | null = null;

  @ViewChild('bindingDialog')
  private bindingDialog?: ElementRef<HTMLElement>;

  protected readonly inventory = signal<PluginInventory | null>(null);
  protected readonly allItems = signal<PluginArtifact[]>([]);
  protected readonly search = signal('');
  protected readonly items = computed(() => {
    const query = this.search().trim().toLocaleLowerCase();
    if (!query) return this.allItems();
    return this.allItems().filter((plugin) => [
      plugin.id,
      plugin.name,
      plugin.version,
      plugin.fileName,
      plugin.status,
    ].some((value) => value?.toLocaleLowerCase().includes(query)));
  });
  protected readonly bindings = signal<PluginBinding[]>([]);
  protected readonly bots = signal<BotConfiguration[]>([]);
  protected readonly runtime = signal<BotRuntimeSummary | null>(null);
  protected readonly deliveries = signal<PluginDeliverySummary[]>([]);
  protected readonly deliveryPage = signal<PluginDeliveryPage | null>(null);
  protected readonly deliveryWarning = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly reloading = signal(false);
  protected readonly loaded = signal(false);
  protected readonly pageError = signal<string | null>(null);
  protected readonly warning = signal<string | null>(null);
  protected readonly detailBotId = signal<string | null>(null);
  protected readonly createDialogOpen = signal(false);
  protected readonly saving = signal(false);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly fileStates = signal<Record<string, BindingFileState>>({});
  protected readonly editorStates = signal<Record<string, BindingEditorState | null>>({});
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
  protected readonly boundBots = computed(() =>
    [...this.bots()]
      .sort((left, right) => left.displayName.localeCompare(right.displayName)),
  );
  protected readonly detailBot = computed(() =>
    this.bots().find((bot) => bot.id === this.detailBotId()) ?? null,
  );

  protected readonly bindingForm = this.formBuilder.group({
    pluginId: ['', Validators.required],
    botId: ['', Validators.required],
    configJson: ['{}', [Validators.required, maxUtf8Bytes(65_536), jsonObject]],
    enabled: [true],
  });

  ngOnInit(): void {
    this.loadAll();
    this.botApi.observeRuntime()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (summary) => this.applyRuntimeSummary(summary),
        error: () => undefined,
      });
    timer(5_000, 5_000)
      .pipe(
        exhaustMap(() => this.botApi.getRuntimeSummary().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((summary) => this.applyRuntimeSummary(summary));
    timer(15_000, 15_000)
      .pipe(
        exhaustMap(() => forkJoin({
          bots: this.botApi.list(),
          bindings: this.api.listBindings(),
        }).pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ bots, bindings }) => {
        this.bots.set(bots);
        this.bindings.set(this.sortBindings(bindings));
      });
  }

  protected loadAll(): void {
    this.loading.set(true);
    this.pageError.set(null);
    forkJoin({
      inventory: this.api.list(),
      bindings: this.api.listBindings(),
      bots: this.botApi.list(),
      runtime: this.botApi.getRuntimeSummary().pipe(catchError(() => of(null))),
      deliveries: this.eventApi.listPluginDeliveries({ limit: 25 }).pipe(catchError(() => of(null))),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ inventory, bindings, bots, runtime, deliveries }) => {
          this.applyInventory(inventory);
          this.bindings.set(this.sortBindings(bindings));
          this.bots.set(bots);
          if (runtime) this.applyRuntimeSummary(runtime);
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
      .list()
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
    forkJoin({
      inventory: this.api.reload(),
      bindings: this.api.listBindings(),
      bots: this.botApi.list(),
      runtime: this.botApi.getRuntimeSummary().pipe(catchError(() => of(null))),
    })
      .pipe(
        finalize(() => this.reloading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ inventory, bindings, bots, runtime }) => {
          this.applyInventory(inventory);
          this.bindings.set(this.sortBindings(bindings));
          this.bots.set(bots);
          if (runtime) this.applyRuntimeSummary(runtime);
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

  protected openCreate(plugin: PluginArtifact, trigger?: Event): void {
    this.captureBindingDialogTrigger(trigger);
    this.createDialogOpen.set(true);
    this.dialogError.set(null);
    const bot = this.availableBots(plugin.id)[0];
    this.bindingForm.reset({
      pluginId: plugin.id,
      botId: bot?.id ?? '',
      configJson: this.prettyJson(plugin.defaultConfigJson || '{}'),
      enabled: true,
    });
    this.focusBindingDialog();
  }

  protected openCreateForBot(bot: BotConfiguration, trigger?: Event): void {
    this.captureBindingDialogTrigger(trigger);
    const plugin = this.availablePluginsForBot(bot.id)[0];
    this.createDialogOpen.set(true);
    this.dialogError.set(null);
    this.bindingForm.reset({
      pluginId: plugin?.id ?? '',
      botId: bot.id,
      configJson: this.prettyJson(plugin?.defaultConfigJson || '{}'),
      enabled: true,
    });
    this.bindingForm.controls.botId.disable();
    this.focusBindingDialog();
  }

  protected selectBindingPlugin(event: Event): void {
    const pluginId = (event.target as HTMLSelectElement).value;
    const plugin = this.allItems().find((item) => item.id === pluginId);
    this.bindingForm.controls.configJson.setValue(this.prettyJson(plugin?.defaultConfigJson || '{}'));
  }

  protected closeDialog(force = false): void {
    if (this.saving() && !force) return;
    this.createDialogOpen.set(false);
    this.bindingForm.controls.botId.enable();
    const trigger = this.bindingDialogTrigger;
    this.bindingDialogTrigger = null;
    queueMicrotask(() => {
      if (trigger?.isConnected && !(trigger instanceof HTMLButtonElement && trigger.disabled)) {
        trigger.focus();
        return;
      }
      document.querySelector<HTMLElement>('.detail-header button, .page-header button')?.focus();
    });
  }

  protected handleBindingDialogKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Tab') return;
    const dialog = this.bindingDialog?.nativeElement;
    if (!dialog) return;
    const focusable = [...dialog.querySelectorAll<HTMLElement>(
      'button:not([disabled]), select:not([disabled]), textarea:not([disabled]), input:not([disabled])',
    )].filter((element) => element.tabIndex >= 0);
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
      event.preventDefault();
      first.focus();
    }
  }

  protected saveBinding(): void {
    if (this.bindingForm.invalid || this.saving()) {
      this.bindingForm.markAllAsTouched();
      return;
    }
    const raw = this.bindingForm.getRawValue();
    this.saving.set(true);
    this.dialogError.set(null);
    this.api.createBinding({
      pluginId: raw.pluginId,
      botId: raw.botId,
      configJson: raw.configJson,
      enabled: raw.enabled,
    })
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (binding) => {
          this.replaceBinding(binding);
          if (this.detailBotId() === binding.botId) this.loadFiles(binding, '');
          this.closeDialog(true);
          this.scan();
        },
        error: (error: unknown) => {
          this.dialogError.set(this.errorMessage(error, '保存插件绑定失败。'));
        },
      });
  }

  protected deleteBinding(binding: PluginBinding): void {
    if (this.isMutating(binding.id) || !window.confirm(
      '确认删除这个机器人插件绑定？此操作会永久删除该绑定文件夹内的全部文件，且无法恢复。',
    )) return;
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

  protected openBotDetail(bot: BotConfiguration): void {
    this.detailBotId.set(bot.id);
    this.bindingsForBot(bot.id).forEach((binding) => this.loadFiles(binding));
  }

  protected closeBotDetail(): void {
    this.detailBotId.set(null);
    this.closeDialog(true);
  }

  protected bindingsForBot(botId: string): PluginBinding[] {
    return this.bindings().filter((binding) => binding.botId === botId);
  }

  protected bindingCountForBot(botId: string): number {
    return this.bindingsForBot(botId).length;
  }

  protected availablePluginsForBot(botId: string): PluginArtifact[] {
    const bound = new Set(this.bindingsForBot(botId).map((binding) => binding.pluginId));
    return this.allItems().filter((plugin) => plugin.loaded && !bound.has(plugin.id));
  }

  protected runtimeFor(botId: string): BotRuntimeStatus | null {
    return this.runtime()?.bots.find((bot) => bot.botId === botId) ?? null;
  }

  protected runtimeLabel(botId: string): string {
    const status = this.runtimeFor(botId);
    if (!status) return '状态未知';
    const labels: Partial<Record<BotRuntimeStatus['state'], string>> = {
      DISABLED: '未启用', STARTING: '启动中', DISCOVERING: '发现中', CONNECTING: '连接中',
      AUTHENTICATING: '认证中', READY: '就绪', ONLINE: '在线', RECONNECTING: '重连中',
      STOPPING: '停止中', FAILED: '异常', STOPPED: '已停止',
    };
    return labels[status.state] ?? status.state;
  }

  protected runtimeClass(botId: string): string {
    const state = this.runtimeFor(botId)?.state;
    if (state === 'READY' || state === 'ONLINE') return 'delivery-success';
    if (state === 'FAILED') return 'delivery-failed';
    return 'delivery-pending';
  }

  protected updatedAtForBot(bot: BotConfiguration): string {
    return this.bindingsForBot(bot.id).reduce(
      (latest, binding) => binding.updatedAt > latest ? binding.updatedAt : latest,
      bot.updatedAt,
    );
  }

  protected fileState(bindingId: string): BindingFileState | null {
    return this.fileStates()[bindingId] ?? null;
  }

  protected editorState(bindingId: string): BindingEditorState | null {
    return this.editorStates()[bindingId] ?? null;
  }

  protected loadFiles(binding: PluginBinding, path = this.fileState(binding.id)?.path ?? ''): void {
    this.setFileState(binding.id, { path, entries: [], loading: true, error: null });
    this.api.listBindingFiles(binding.id, path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (listing) => this.setFileState(binding.id, {
          ...listing,
          entries: this.sortFileEntries(listing.entries),
          loading: false,
          error: null,
        }),
        error: (error: unknown) => this.setFileState(binding.id, {
          path, entries: [], loading: false,
          error: this.errorMessage(error, '无法读取插件文件夹。'),
        }),
      });
  }

  protected openFileEntry(binding: PluginBinding, entry: PluginFileEntry): void {
    if (entry.directory) {
      this.closeEditor(binding.id);
      this.loadFiles(binding, entry.path);
    } else if (entry.name.toLowerCase().endsWith('.json')) {
      this.openJsonEditor(binding, entry);
    } else {
      this.downloadFile(binding, entry);
    }
  }

  protected navigateBreadcrumb(binding: PluginBinding, path: string): void {
    this.closeEditor(binding.id);
    this.loadFiles(binding, path);
  }

  protected breadcrumbs(bindingId: string): Array<{ label: string; path: string }> {
    const segments = (this.fileState(bindingId)?.path ?? '').split('/').filter(Boolean);
    return [{ label: '根目录', path: '' }, ...segments.map((segment, index) => ({
      label: segment,
      path: segments.slice(0, index + 1).join('/'),
    }))];
  }

  protected createFileEntry(binding: PluginBinding, directory: boolean): void {
    if (this.isMutating(binding.id)) return;
    const name = window.prompt(directory ? '文件夹名称' : '文件名称');
    if (!name?.trim()) return;
    const path = this.joinPath(this.fileState(binding.id)?.path ?? '', name.trim());
    this.setMutating(binding.id, true);
    this.api.createBindingFileEntry(binding.id, { path, directory })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.refreshBindingsAfterFileMutation(binding),
        error: (error: unknown) => {
          this.setMutating(binding.id, false);
          this.setFileError(binding.id, this.errorMessage(error, '创建失败。'));
        },
      });
  }

  protected uploadBindingFile(binding: PluginBinding, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.isMutating(binding.id)) return;
    const existing = this.fileState(binding.id)?.entries.find((entry) => entry.name === file.name);
    if (existing?.directory) {
      this.setFileError(binding.id, `无法上传“${file.name}”：当前目录存在同名文件夹。`);
      return;
    }
    const overwrite = existing !== undefined;
    if (overwrite && !window.confirm(`文件“${file.name}”已存在，确认覆盖？原文件将无法恢复。`)) return;
    this.setMutating(binding.id, true);
    this.api.uploadBindingFile(
      binding.id,
      this.fileState(binding.id)?.path ?? '',
      file,
      overwrite,
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.refreshBindingsAfterFileMutation(binding),
        error: (error: unknown) => {
          this.setMutating(binding.id, false);
          this.setFileError(binding.id, this.errorMessage(error, '上传文件失败。'));
        },
      });
  }

  protected downloadFile(binding: PluginBinding, entry: PluginFileEntry): void {
    this.api.downloadBindingFile(binding.id, entry.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          const anchor = document.createElement('a');
          anchor.href = url;
          anchor.download = entry.name;
          anchor.click();
          URL.revokeObjectURL(url);
        },
        error: (error: unknown) => this.setFileError(binding.id, this.errorMessage(error, '下载文件失败。')),
      });
  }

  protected deleteFile(binding: PluginBinding, entry: PluginFileEntry, event: Event): void {
    event.stopPropagation();
    if (this.isMutating(binding.id)) return;
    if (!window.confirm(`确认删除${entry.directory ? '文件夹' : '文件'}“${entry.name}”？`)) return;
    this.setMutating(binding.id, true);
    this.api.deleteBindingFile(binding.id, entry.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.refreshBindingsAfterFileMutation(binding),
        error: (error: unknown) => {
          this.setMutating(binding.id, false);
          this.setFileError(binding.id, this.errorMessage(error, '删除文件失败。'));
        },
      });
  }

  protected openJsonEditor(binding: PluginBinding, entry: PluginFileEntry): void {
    this.api.getBindingFileContent(binding.id, entry.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (file) => this.setEditorState(binding.id, {
          ...file,
          content: this.prettyJson(file.content),
          saving: false,
          error: null,
        }),
        error: (error: unknown) => this.setFileError(binding.id, this.errorMessage(error, '无法打开 JSON 文件。')),
      });
  }

  protected updateEditorContent(bindingId: string, event: Event): void {
    const editor = this.editorState(bindingId);
    if (!editor) return;
    this.setEditorState(bindingId, { ...editor, content: (event.target as HTMLTextAreaElement).value });
  }

  protected saveEditor(binding: PluginBinding): void {
    const editor = this.editorState(binding.id);
    if (!editor || editor.saving || this.isMutating(binding.id)) return;
    try { JSON.parse(editor.content); }
    catch {
      this.setEditorState(binding.id, { ...editor, error: '内容不是有效的 JSON。' });
      return;
    }
    this.setEditorState(binding.id, { ...editor, saving: true, error: null });
    this.setMutating(binding.id, true);
    this.api.saveBindingFileContent(binding.id, {
      path: editor.path,
      content: editor.content,
      expectedSha256: editor.sha256,
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.setEditorState(binding.id, { ...saved, content: this.prettyJson(saved.content), saving: false, error: null });
        this.refreshBindingsAfterFileMutation(binding);
      },
      error: (error: unknown) => {
        this.setMutating(binding.id, false);
        this.setEditorState(binding.id, {
          ...editor, saving: false, error: this.errorMessage(error, '保存 JSON 文件失败。'),
        });
      },
    });
  }

  protected closeEditor(bindingId: string): void {
    this.setEditorState(bindingId, null);
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
    return this.allItems().find((item) => item.id === pluginId)?.name || pluginId;
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
    if (control.hasError('maxUtf8Bytes')) return '配置的 UTF-8 内容不能超过 64 KiB';
    return '配置必须是有效的 JSON 对象';
  }

  @HostListener('document:keydown.escape')
  protected handleEscape(): void {
    if (this.createDialogOpen()) this.closeDialog();
  }

  private applyInventory(inventory: PluginInventory): void {
    this.inventory.set(inventory);
    this.allItems.set(inventory.items);
    this.loaded.set(true);
    this.pageError.set(null);
    this.warning.set(inventory.scanError);
  }

  private applyRuntimeSummary(summary: BotRuntimeSummary): void {
    const current = this.runtime();
    if (current?.observedAt && summary.observedAt && summary.observedAt < current.observedAt) return;
    this.runtime.set(summary);
  }

  private captureBindingDialogTrigger(event?: Event): void {
    const currentTarget = event?.currentTarget;
    this.bindingDialogTrigger = currentTarget instanceof HTMLElement
      ? currentTarget
      : document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }

  private focusBindingDialog(): void {
    queueMicrotask(() => {
      const dialog = this.bindingDialog?.nativeElement;
      const target = dialog?.querySelector<HTMLElement>('select:not([disabled])')
        ?? dialog?.querySelector<HTMLElement>('textarea:not([disabled]), button:not([disabled])');
      target?.focus();
    });
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

  private setFileState(id: string, state: BindingFileState): void {
    this.fileStates.update((current) => ({ ...current, [id]: state }));
  }

  private refreshBindingsAfterFileMutation(binding: PluginBinding): void {
    const path = this.fileState(binding.id)?.path ?? '';
    this.api.listBindings()
      .pipe(
        finalize(() => this.setMutating(binding.id, false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (bindings) => {
          this.bindings.set(this.sortBindings(bindings));
          this.loadFiles(bindings.find((item) => item.id === binding.id) ?? binding, path);
        },
        error: () => {
          this.warning.set('插件文件已更新，但绑定状态刷新失败，请重新加载插件状态。');
          this.loadFiles(binding, path);
        },
      });
  }

  private setFileError(id: string, error: string): void {
    const current = this.fileState(id) ?? { path: '', entries: [], loading: false, error: null };
    this.setFileState(id, { ...current, error });
  }

  private setEditorState(id: string, state: BindingEditorState | null): void {
    this.editorStates.update((current) => ({ ...current, [id]: state }));
  }

  private sortFileEntries(entries: PluginFileEntry[]): PluginFileEntry[] {
    return [...entries].sort(
      (left, right) => Number(right.directory) - Number(left.directory)
        || left.name.localeCompare(right.name),
    );
  }

  private joinPath(directory: string, name: string): string {
    return directory ? `${directory}/${name}` : name;
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
