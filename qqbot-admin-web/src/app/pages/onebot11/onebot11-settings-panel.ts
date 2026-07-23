import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideRefreshCw, LucideSave } from '@lucide/angular';
import { finalize } from 'rxjs/operators';

interface OneBotSettings {
  botId: string;
  enabled: boolean;
  forwardEnabled: boolean;
  forwardBindAddress: string;
  forwardPort: number | null;
  reverseEnabled: boolean;
  reverseUrl: string | null;
  accessTokenConfigured: boolean;
  heartbeatEnabled: boolean;
  heartbeatIntervalMs: number;
  reconnectIntervalMs: number;
  revision: number;
}

interface OneBotRuntime {
  state: string;
  forwardListening: boolean;
  forwardConnections: number;
  reverseConnected: boolean;
  lastError: string | null;
}

interface OneBotResponse {
  settings: OneBotSettings;
  runtime: OneBotRuntime;
}

@Component({
  selector: 'qqbot-onebot11-settings-panel',
  imports: [ReactiveFormsModule, LucideRefreshCw, LucideSave],
  templateUrl: './onebot11-settings-panel.html',
  styleUrl: './onebot11-settings-panel.scss',
})
export class OneBot11SettingsPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly forms = inject(NonNullableFormBuilder);
  private botId = '';

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly tokenConfigured = signal(false);
  protected readonly runtime = signal<OneBotRuntime | null>(null);
  protected revision = 0;

  protected readonly form = this.forms.group({
    enabled: false,
    forwardEnabled: false,
    forwardBindAddress: ['127.0.0.1', [Validators.required, Validators.maxLength(255)]],
    forwardPort: [5700, [Validators.required, Validators.min(1), Validators.max(65535)]],
    reverseEnabled: false,
    reverseUrl: ['', Validators.maxLength(2048)],
    accessToken: ['', Validators.maxLength(4096)],
    clearAccessToken: false,
    heartbeatEnabled: true,
    heartbeatIntervalMs: [15000, [Validators.required, Validators.min(1000), Validators.max(300000)]],
    reconnectIntervalMs: [3000, [Validators.required, Validators.min(500), Validators.max(300000)]],
  });

  ngOnInit(): void {
    this.botId = this.host.nativeElement.getAttribute('bot-id') ?? '';
    if (!this.botId) {
      this.loading.set(false);
      this.error.set('机器人标识无效。');
      return;
    }
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.saved.set(false);
    this.http
      .get<OneBotResponse>(this.url)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => this.apply(response),
        error: (error: unknown) => this.error.set(this.errorMessage(error, '无法加载 OneBot 配置。')),
      });
  }

  protected save(): void {
    this.error.set(null);
    this.saved.set(false);
    if (!this.validTransportCombination()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.form.disable();
    this.http
      .put<OneBotResponse>(this.url, {
        expectedRevision: this.revision,
        enabled: value.enabled,
        forwardEnabled: value.forwardEnabled,
        forwardBindAddress: value.forwardBindAddress.trim(),
        forwardPort: value.forwardEnabled ? value.forwardPort : null,
        reverseEnabled: value.reverseEnabled,
        reverseUrl: value.reverseEnabled ? value.reverseUrl.trim() : null,
        accessToken: value.accessToken.trim() || null,
        clearAccessToken: value.clearAccessToken,
        heartbeatEnabled: value.heartbeatEnabled,
        heartbeatIntervalMs: value.heartbeatIntervalMs,
        reconnectIntervalMs: value.reconnectIntervalMs,
      })
      .pipe(
        finalize(() => {
          this.saving.set(false);
          this.form.enable();
        }),
      )
      .subscribe({
        next: (response) => {
          this.apply(response);
          this.saved.set(true);
        },
        error: (error: unknown) => {
          this.error.set(this.errorMessage(error, '无法保存 OneBot 配置。'));
        },
      });
  }

  protected stateLabel(runtime: OneBotRuntime): string {
    switch (runtime.state) {
      case 'RUNNING': return '运行中';
      case 'CONNECTING': return '连接中';
      case 'WAITING_FOR_BOT': return '等待机器人在线';
      case 'FAILED': return '启动失败';
      case 'STOPPED': return '已停止';
      default: return '未启用';
    }
  }

  private validTransportCombination(): boolean {
    const value = this.form.getRawValue();
    if (value.enabled && !value.forwardEnabled && !value.reverseEnabled) {
      this.error.set('启用 OneBot 时至少选择一种 WebSocket 模式。');
      return false;
    }
    if (value.reverseEnabled && !value.reverseUrl.trim()) {
      this.error.set('启用反向 WebSocket 时必须填写完整 URL。');
      return false;
    }
    if (
      value.enabled &&
      !this.tokenConfigured() &&
      !value.accessToken.trim()
    ) {
      this.error.set('启用 OneBot 时必须设置 access token。');
      return false;
    }
    return true;
  }

  private apply(response: OneBotResponse): void {
    const settings = response.settings;
    this.revision = settings.revision;
    this.tokenConfigured.set(settings.accessTokenConfigured);
    this.runtime.set(response.runtime);
    this.form.reset({
      enabled: settings.enabled,
      forwardEnabled: settings.forwardEnabled,
      forwardBindAddress: settings.forwardBindAddress,
      forwardPort: settings.forwardPort ?? 5700,
      reverseEnabled: settings.reverseEnabled,
      reverseUrl: settings.reverseUrl ?? '',
      accessToken: '',
      clearAccessToken: false,
      heartbeatEnabled: settings.heartbeatEnabled,
      heartbeatIntervalMs: settings.heartbeatIntervalMs,
      reconnectIntervalMs: settings.reconnectIntervalMs,
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const body = error.error as { message?: unknown } | null;
      if (typeof body?.message === 'string' && body.message.trim()) {
        return body.message;
      }
      if (error.status === 409) {
        return '配置已在其他页面修改，请重新载入。';
      }
    }
    return fallback;
  }

  private get url(): string {
    return `/api/bots/${encodeURIComponent(this.botId)}/onebot11`;
  }
}
