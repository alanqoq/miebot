import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { BotApiService, BotRuntimeSummary } from '../../core/bot-api.service';
import { EventApiService } from '../../core/event-api.service';
import { PluginApiService } from '../../core/plugin-api.service';
import { HealthStatus, SystemApiService, SystemInfo } from '../../core/system-api.service';
import { DashboardPage } from './dashboard-page';

describe('DashboardPage', () => {
  const systemInfo: SystemInfo = {
    service: 'qqbot',
    version: '0.1.0',
    status: 'UP',
    database: 'SQLite',
    serverTime: '2026-07-18T12:00:00Z',
  };
  const readiness: HealthStatus = {
    status: 'UP',
    checkedAt: '2026-07-18T12:00:00Z',
    components: { database: 'UP' },
  };
  const runtimeSummary: BotRuntimeSummary = {
    totalCount: 3,
    enabledCount: 2,
    connectedCount: 1,
    observedAt: '2026-07-18T12:00:00Z',
    bots: [],
  };

  const getSystemInfo = vi.fn();
  const getLiveness = vi.fn();
  const getReadiness = vi.fn();
  const getRuntimeSummary = vi.fn();
  const getOutboxStats = vi.fn();
  const listInbox = vi.fn();
  const listPlugins = vi.fn();

  beforeEach(async () => {
    getSystemInfo.mockReset().mockReturnValue(of(systemInfo));
    getLiveness.mockReset().mockReturnValue(of({
      status: 'UP',
      checkedAt: '2026-07-18T12:00:00Z',
      components: { process: 'UP' },
    } satisfies HealthStatus));
    getReadiness.mockReset().mockReturnValue(of(readiness));
    getRuntimeSummary.mockReset().mockReturnValue(of(runtimeSummary));
    getOutboxStats.mockReset().mockReturnValue(
      of({
        totalCount: 4,
        pendingCount: 1,
        inProgressCount: 1,
        retryWaitCount: 1,
        succeededCount: 1,
        resultUnknownCount: 0,
        deadLetterCount: 0,
      }),
    );
    listInbox.mockReset().mockReturnValue(
      of({ items: [], nextCursor: null, hasMore: false, observedAt: '2026-07-18T12:00:00Z' }),
    );
    listPlugins.mockReset().mockReturnValue(
      of({
        items: [],
        directory: '/plugins',
        directoryExists: true,
        runtimeAvailable: false,
        scanError: null,
        scannedAt: '2026-07-18T12:00:00Z',
      }),
    );

    await TestBed.configureTestingModule({
      imports: [DashboardPage],
      providers: [
        {
          provide: SystemApiService,
          useValue: { getSystemInfo, getLiveness, getReadiness },
        },
        {
          provide: BotApiService,
          useValue: { getRuntimeSummary },
        },
        {
          provide: EventApiService,
          useValue: { getOutboxStats, listInbox },
        },
        {
          provide: PluginApiService,
          useValue: { list: listPlugins },
        },
      ],
    }).compileComponents();
  });

  it('renders the connected count returned by the runtime API', () => {
    const fixture = createFixture();

    expect(metricText(fixture, '运行机器人')).toContain('1');
    expect(metricText(fixture, '运行机器人')).toContain('1 个已连接 / 2 个已启用');
  });

  it('keeps platform health visible when only runtime loading fails', () => {
    getRuntimeSummary.mockReturnValue(throwError(() => new Error('runtime unavailable')));

    const fixture = createFixture();

    expect(fixture.nativeElement.textContent).toContain('正常');
    expect(fixture.nativeElement.textContent).toContain('0.1.0');
    expect(metricText(fixture, '运行机器人')).toContain('--');
    expect(metricText(fixture, '运行机器人')).toContain('运行状态不可用');
    expect(fixture.nativeElement.textContent).toContain('机器人运行状态暂不可用');
  });

  it('renders the application process state returned by the liveness API', () => {
    getLiveness.mockReturnValue(of({
      status: 'DOWN',
      checkedAt: '2026-07-18T12:00:00Z',
      components: { process: 'DOWN' },
    } satisfies HealthStatus));

    const fixture = createFixture();
    const processRow = Array.from(
      fixture.nativeElement.querySelectorAll('.health-row') as NodeListOf<HTMLElement>,
    ).find((row) => row.textContent?.includes('应用进程'));

    expect(processRow?.textContent).toContain('DOWN');
    expect(processRow?.querySelector('.health-value')?.classList.contains('health-up')).toBe(false);
  });

  it('renders queue and plugin metrics from their APIs instead of fixed zeros', () => {
    const fixture = createFixture();

    expect(metricText(fixture, '插件绑定')).toContain('0');
    expect(metricText(fixture, '插件绑定')).toContain('发现 0 个制品');
    expect(metricText(fixture, '待处理任务')).toContain('3');
    expect(metricText(fixture, '待处理任务')).toContain('0 个死信 / 4 个任务');
    expect(getOutboxStats).toHaveBeenCalledWith();
    expect(listInbox).toHaveBeenCalledWith({ limit: 5 });
    expect(listPlugins).toHaveBeenCalledWith();
  });

  it('polls runtime every five seconds and stops after destruction', async () => {
    vi.useFakeTimers();
    try {
      const fixture = createFixture();
      expect(getRuntimeSummary).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(5000);
      fixture.detectChanges();
      expect(getRuntimeSummary).toHaveBeenCalledTimes(2);

      fixture.destroy();
      await vi.advanceTimersByTimeAsync(5000);
      expect(getRuntimeSummary).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('refreshes operational metrics every ten seconds', async () => {
    vi.useFakeTimers();
    try {
      const fixture = createFixture();
      expect(getOutboxStats).toHaveBeenCalledTimes(1);
      expect(listInbox).toHaveBeenCalledTimes(1);
      expect(listPlugins).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(10000);
      await Promise.resolve();
      fixture.detectChanges();

      expect(getOutboxStats).toHaveBeenCalledTimes(2);
      expect(listInbox).toHaveBeenCalledTimes(2);
      expect(listPlugins).toHaveBeenCalledTimes(1);
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });
});

function createFixture(): ComponentFixture<DashboardPage> {
  const fixture = TestBed.createComponent(DashboardPage);
  fixture.detectChanges();
  return fixture;
}

function metricText(fixture: ComponentFixture<DashboardPage>, label: string): string {
  const cards = fixture.nativeElement.querySelectorAll('.metric-card') as NodeListOf<HTMLElement>;
  const card = Array.from(cards).find((candidate) => candidate.textContent?.includes(label));
  if (!card) {
    throw new Error(`Missing metric card: ${label}`);
  }
  return card.textContent ?? '';
}
