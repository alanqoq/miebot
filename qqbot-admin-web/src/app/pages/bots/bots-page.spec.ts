import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import {
  BotApiService,
  BotConfiguration,
  BotRuntimeStatus,
  BotRuntimeSummary,
  CreateBotRequest,
  UpdateBotRequest,
} from '../../core/bot-api.service';
import { DEFAULT_GATEWAY_INTENTS } from '../../core/gateway-intents';
import { BotsPage } from './bots-page';

describe('BotsPage', () => {
  const bot: BotConfiguration = {
    id: '550e8400-e29b-41d4-a716-446655440000',
    displayName: 'Support Bot',
    appId: '1029384756',
    environment: 'SANDBOX',
    intents: 512,
    shardIndex: 0,
    shardCount: 1,
    enabled: true,
    revision: 3,
    createdAt: '2026-07-16T12:00:00Z',
    updatedAt: '2026-07-16T12:05:00Z',
    secretConfigured: true,
    maxMediaUploadBytes: 16 * 1024 * 1024,
  };

  const revisionConflict = new HttpErrorResponse({
    status: 409,
    error: {
      code: 'REVISION_CONFLICT',
      message: 'Configuration revision changed',
      fieldErrors: {},
      traceId: 'test-trace',
      timestamp: '2026-07-16T12:06:00Z',
    },
  });

  it('shows the actual Gateway state, session and sanitized error', async () => {
    const summary = runtimeSummary({
      state: 'RECONNECTING',
      session: { id: 'gateway-session-1234567890', sequence: 42 },
      reconnectCount: 3,
      lastError: {
        code: 'TRANSPORT_FAILURE',
        message: 'QQ Gateway transport failed',
        occurredAt: '2026-07-18T12:00:00Z',
      },
    });
    const { fixture } = await configure({ runtime: summary });

    expect(fixture.nativeElement.textContent).toContain('重连中');
    expect(fixture.nativeElement.textContent).toContain('Seq 42');
    expect(fixture.nativeElement.textContent).toContain('重连 3 次');
    expect(fixture.nativeElement.textContent).toContain('TRANSPORT_FAILURE');
    expect(fixture.nativeElement.textContent).not.toContain('在线');
  });

  it('uses group and C2C events by default when creating a bot', async () => {
    const { fixture, api } = await configure();

    click(fixture, buttonByText(fixture, '新增机器人'));
    const defaultIntent = intentCheckbox(fixture, '群聊与单聊消息');
    expect(defaultIntent.checked).toBe(true);
    fill(fixture, 'input[formControlName="displayName"]', 'New Bot');
    fill(fixture, 'input[formControlName="appId"]', '20001');
    fill(fixture, 'input[formControlName="appSecret"]', 'write-only-secret');
    click(fixture, buttonByText(fixture, '创建机器人'));

    const request = api.create.mock.calls[0]?.[0] as CreateBotRequest;
    expect(request.intents).toBe(DEFAULT_GATEWAY_INTENTS);
  });

  it('preserves unknown intent bits when known options are edited', async () => {
    const unknownBit = 2 ** 40;
    const configured = { ...bot, intents: unknownBit + DEFAULT_GATEWAY_INTENTS };
    const { fixture, api } = await configure({ configuredBot: configured });

    click(fixture, element(fixture, 'button[aria-label="编辑机器人"]'));
    click(fixture, intentCheckbox(fixture, '互动事件'));
    click(fixture, buttonByText(fixture, '保存更改'));

    const request = api.update.mock.calls[0]?.[1] as UpdateBotRequest;
    expect(request.intents).toBe(unknownBit + DEFAULT_GATEWAY_INTENTS + 2 ** 26);
  });

  it('saves the configured per-bot media upload limit in bytes', async () => {
    const { fixture, api } = await configure();

    click(fixture, element(fixture, 'button[aria-label="编辑机器人"]'));
    fill(fixture, 'input[formControlName="maxMediaUploadMiB"]', '24');
    click(fixture, buttonByText(fixture, '保存更改'));

    const request = api.update.mock.calls[0]?.[1] as UpdateBotRequest;
    expect(request.maxMediaUploadBytes).toBe(24 * 1024 * 1024);
  });

  it('rejects a selected web media file before upload when it exceeds the bot limit', async () => {
    const configured = { ...bot, maxMediaUploadBytes: 1024 * 1024 };
    const { fixture, api } = await configure({ configuredBot: configured });

    click(fixture, element(fixture, 'button[aria-label="发送测试消息"]'));
    const kind = element<HTMLSelectElement>(fixture, 'select[formControlName="kind"]');
    kind.value = 'MEDIA';
    kind.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    const input = element<HTMLInputElement>(fixture, 'input[type="file"]');
    const oversized = new File([new Uint8Array(1024 * 1024 + 1)], 'large.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { configurable: true, value: [oversized] });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('文件超过该机器人配置的上限');
    expect(api.uploadMedia).not.toHaveBeenCalled();
  });

  it('fills a markdown-backed keyboard payload when the rich message kind changes', async () => {
    const { fixture } = await configure();

    click(fixture, element(fixture, 'button[aria-label="发送测试消息"]'));
    const kind = element<HTMLSelectElement>(fixture, 'select[formControlName="kind"]');
    kind.value = 'KEYBOARD';
    kind.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    const payload = JSON.parse(
      element<HTMLTextAreaElement>(fixture, 'textarea[formControlName="payloadJson"]').value,
    ) as { markdown: { content: string }; keyboard: { content: { rows: unknown[] } } };
    expect(payload.markdown.content).toBeTruthy();
    expect(payload.keyboard.content.rows).toHaveLength(1);
  });

  it('keeps AppSecret empty and offers reload when an edit conflicts', async () => {
    const { fixture, api } = await configure({ updateError: revisionConflict });

    click(fixture, element(fixture, 'button[aria-label="编辑机器人"]'));
    const secretInput = element<HTMLInputElement>(fixture, 'input[formcontrolname="appSecret"]');
    expect(secretInput.value).toBe('');
    click(fixture, buttonByText(fixture, '保存更改'));

    expect(api.update).toHaveBeenCalledTimes(1);
    const request = api.update.mock.calls[0]?.[1] as UpdateBotRequest;
    expect(request.expectedRevision).toBe(3);
    expect(request).not.toHaveProperty('appSecret');
    expect(fixture.nativeElement.textContent).toContain('配置版本已变化');
    expect(fixture.nativeElement.textContent).toContain('重新载入');
  });

  it('refreshes runtime state after toggling the configured enabled flag', async () => {
    const disabled = { ...bot, enabled: false, revision: 4 };
    const { fixture, api } = await configure({ enabledResult: disabled });

    click(fixture, element(fixture, `button[aria-label="停用${bot.displayName}"]`));

    expect(api.setEnabled).toHaveBeenCalledWith(bot.id, {
      expectedRevision: 3,
      enabled: false,
    });
    expect(api.getRuntimeSummary).toHaveBeenCalledTimes(2);
  });

  it('polls runtime state every five seconds', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, api } = await configure();
      expect(api.getRuntimeSummary).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(5000);
      fixture.detectChanges();

      expect(api.getRuntimeSummary).toHaveBeenCalledTimes(2);
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });
});

interface ConfigureOptions {
  configuredBot?: BotConfiguration;
  runtime?: BotRuntimeSummary;
  enabledResult?: BotConfiguration;
  updateError?: HttpErrorResponse;
}

async function configure(options: ConfigureOptions = {}) {
  const configuredBot = options.configuredBot ?? botFixture();
  const api = {
    list: vi.fn(() => of([configuredBot])),
    get: vi.fn(() => of(configuredBot)),
    getRuntimeSummary: vi.fn(() => of(options.runtime ?? runtimeSummary())),
    observeRuntime: vi.fn(() => NEVER),
    create: vi.fn((_request: CreateBotRequest) => of(configuredBot)),
    update: options.updateError
      ? vi.fn((_id: string, _request: UpdateBotRequest) => throwError(() => options.updateError))
      : vi.fn((_id: string, _request: UpdateBotRequest) => of(configuredBot)),
    setEnabled: vi.fn(() => of(options.enabledResult ?? configuredBot)),
    delete: vi.fn(() => of(undefined)),
    uploadMedia: vi.fn(),
    sendMessage: vi.fn(),
  };
  await TestBed.configureTestingModule({
    imports: [BotsPage],
    providers: [{ provide: BotApiService, useValue: api }],
  }).compileComponents();
  const fixture = TestBed.createComponent(BotsPage);
  fixture.detectChanges();
  return { fixture, api };
}

function botFixture(): BotConfiguration {
  return {
    id: '550e8400-e29b-41d4-a716-446655440000',
    displayName: 'Support Bot',
    appId: '1029384756',
    environment: 'SANDBOX',
    intents: 512,
    shardIndex: 0,
    shardCount: 1,
    enabled: true,
    revision: 3,
    createdAt: '2026-07-16T12:00:00Z',
    updatedAt: '2026-07-16T12:05:00Z',
    secretConfigured: true,
    maxMediaUploadBytes: 16 * 1024 * 1024,
  };
}

function runtimeSummary(overrides: Partial<BotRuntimeStatus> = {}): BotRuntimeSummary {
  const configuredBot = botFixture();
  return {
    totalCount: 1,
    enabledCount: 1,
    connectedCount: 0,
    observedAt: '2026-07-18T12:00:00Z',
    bots: [
      {
        botId: configuredBot.id,
        configurationRevision: configuredBot.revision,
        enabled: true,
        state: 'CONNECTING',
        stateChangedAt: '2026-07-18T11:59:00Z',
        connectedAt: null,
        lastHeartbeatAt: null,
        lastDispatchAt: null,
        reconnectCount: 0,
        session: null,
        lastError: null,
        ...overrides,
      },
    ],
  };
}

function element<T extends HTMLElement = HTMLElement>(
  fixture: ComponentFixture<BotsPage>,
  selector: string,
): T {
  const result = fixture.nativeElement.querySelector(selector) as T | null;
  if (!result) {
    throw new Error(`Missing element: ${selector}`);
  }
  return result;
}

function buttonByText(fixture: ComponentFixture<BotsPage>, label: string): HTMLButtonElement {
  const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
  const button = Array.from(buttons).find((candidate) => candidate.textContent?.includes(label));
  if (!button) {
    throw new Error(`Missing button: ${label}`);
  }
  return button;
}

function intentCheckbox(fixture: ComponentFixture<BotsPage>, label: string): HTMLInputElement {
  const options = fixture.nativeElement.querySelectorAll(
    '.intent-option',
  ) as NodeListOf<HTMLLabelElement>;
  const option = Array.from(options).find((candidate) => candidate.textContent?.includes(label));
  if (!option) {
    throw new Error(`Missing intent option: ${label}`);
  }
  return option.querySelector('input') as HTMLInputElement;
}

function fill(fixture: ComponentFixture<BotsPage>, selector: string, value: string): void {
  const input = element<HTMLInputElement>(fixture, selector);
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  fixture.detectChanges();
}

function click(fixture: ComponentFixture<BotsPage>, target: HTMLElement): void {
  target.click();
  fixture.detectChanges();
}
