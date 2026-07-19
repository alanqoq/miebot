import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  BotApiService,
  BotConfiguration,
  BotRuntimeSummary,
  CreateBotRequest,
  SetBotEnabledRequest,
  UpdateBotRequest,
} from './bot-api.service';

describe('BotApiService', () => {
  let api: BotApiService;
  let http: HttpTestingController;

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
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(BotApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists and loads bot configurations', () => {
    let listed: BotConfiguration[] | undefined;
    let loaded: BotConfiguration | undefined;

    api.list().subscribe((value) => (listed = value));
    http.expectOne('/api/bots').flush([bot]);
    api.get(bot.id).subscribe((value) => (loaded = value));
    http.expectOne(`/api/bots/${bot.id}`).flush(bot);

    expect(listed).toEqual([bot]);
    expect(loaded).toEqual(bot);
  });

  it('loads the aggregate bot runtime status', () => {
    const summary: BotRuntimeSummary = {
      totalCount: 1,
      enabledCount: 1,
      connectedCount: 1,
      observedAt: '2026-07-18T12:00:00Z',
      bots: [
        {
          botId: bot.id,
          configurationRevision: bot.revision,
          enabled: true,
          state: 'READY',
          stateChangedAt: '2026-07-18T11:59:00Z',
          connectedAt: '2026-07-18T11:59:00Z',
          lastHeartbeatAt: '2026-07-18T12:00:00Z',
          lastDispatchAt: null,
          reconnectCount: 0,
          session: { id: 'session-1', sequence: 42 },
          lastError: null,
        },
      ],
    };
    let loaded: BotRuntimeSummary | undefined;

    api.getRuntimeSummary().subscribe((value) => (loaded = value));

    const pending = http.expectOne('/api/bots/runtime');
    expect(pending.request.method).toBe('GET');
    pending.flush(summary);
    expect(loaded).toEqual(summary);
  });

  it('creates a bot with the write-only AppSecret', () => {
    const request: CreateBotRequest = {
      displayName: 'Support Bot',
      appId: '1029384756',
      environment: 'SANDBOX',
      intents: 512,
      shardIndex: 0,
      shardCount: 1,
      enabled: true,
      appSecret: 'write-only-secret',
    };

    api.create(request).subscribe();

    const pending = http.expectOne('/api/bots');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual(request);
    pending.flush(bot);
  });

  it('updates a bot with the expected revision and no secret by default', () => {
    const request: UpdateBotRequest = {
      expectedRevision: 3,
      displayName: 'Renamed Bot',
      appId: '1029384756',
      environment: 'PRODUCTION',
      intents: 1024,
      shardIndex: 1,
      shardCount: 2,
    };

    api.update(bot.id, request).subscribe();

    const pending = http.expectOne(`/api/bots/${bot.id}`);
    expect(pending.request.method).toBe('PUT');
    expect(pending.request.body).toEqual(request);
    expect(pending.request.body).not.toHaveProperty('appSecret');
    pending.flush({ ...bot, ...request, revision: 4 });
  });

  it('patches enabled state with the expected revision', () => {
    const request: SetBotEnabledRequest = { expectedRevision: 3, enabled: false };

    api.setEnabled(bot.id, request).subscribe();

    const pending = http.expectOne(`/api/bots/${bot.id}/enabled`);
    expect(pending.request.method).toBe('PATCH');
    expect(pending.request.body).toEqual(request);
    pending.flush({ ...bot, enabled: false, revision: 4 });
  });
});
