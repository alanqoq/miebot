import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { vi } from 'vitest';
import {
  EventApiService,
  InboxEventDetail,
  InboxEventPage,
  InboxEventSummary,
  InboxQuery,
  OutboxJobDetail,
  OutboxJobPage,
  OutboxJobSummary,
  OutboxQuery,
  OutboxQueueStats,
} from '../../core/event-api.service';
import { EventsPage } from './events-page';

describe('EventsPage', () => {
  it('loads and renders Inbox events from the API', async () => {
    const { fixture, api } = await configure();

    expect(api.listInbox).toHaveBeenCalledWith({ limit: 50 });
    expect(api.getOutboxStats).toHaveBeenCalledWith();
    expect(api.getDlqStats).toHaveBeenCalledWith();
    expect(fixture.nativeElement.textContent).toContain('MESSAGE_CREATE');
    expect(fixture.nativeElement.textContent).toContain('Support Bot');
    expect(fixture.nativeElement.textContent).toContain('已接收');
    expect(fixture.nativeElement.textContent).toContain('已加载 1 条');
  });

  it('shows a retryable error when the first Inbox request fails', async () => {
    const apiError = new Error('database unavailable');
    const { fixture, api } = await configure({ listError: apiError });

    expect(fixture.nativeElement.textContent).toContain('无法加载 Inbox 事件');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();

    api.listInbox.mockReturnValueOnce(of(pageFixture()));
    buttonByText(fixture, '重试').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('MESSAGE_CREATE');
  });

  it('keeps the last successful rows when a refresh fails', async () => {
    const { fixture, api } = await configure();
    api.listInbox.mockReturnValueOnce(throwError(() => new Error('temporary failure')));

    buttonByText(fixture, '刷新').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Support Bot');
    expect(fixture.nativeElement.textContent).toContain('当前显示上次成功加载的结果');
  });

  it('sends status and bot filters to the API and can clear them', async () => {
    const { fixture, api } = await configure({ page: emptyPage() });
    const botInput = element<HTMLInputElement>(fixture, 'input[aria-label="按机器人 ID 筛选"]');
    botInput.value = 'bot-filter';
    botInput.dispatchEvent(new Event('input', { bubbles: true }));
    const statusSelect = element<HTMLSelectElement>(fixture, 'select[aria-label="按状态筛选"]');
    statusSelect.value = 'PROCESSING';
    statusSelect.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    expect(api.listInbox).toHaveBeenLastCalledWith({
      limit: 50,
      botId: 'bot-filter',
      status: 'PROCESSING',
    });

    buttonByText(fixture, '清除筛选').click();
    fixture.detectChanges();
    expect(api.listInbox).toHaveBeenLastCalledWith({ limit: 50 });
  });

  it('debounces free-text search before reloading', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, api } = await configure({ page: emptyPage() });
      const input = element<HTMLInputElement>(fixture, 'input[aria-label="搜索 Inbox 事件"]');
      input.value = 'MESSAGE';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      expect(api.listInbox).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(299);
      expect(api.listInbox).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1);
      expect(api.listInbox).toHaveBeenCalledTimes(2);
      expect(api.listInbox).toHaveBeenLastCalledWith({ limit: 50, query: 'MESSAGE' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('appends the next page without duplicating an event', async () => {
    const first = pageFixture({ nextCursor: 'next', hasMore: true });
    const second = {
      ...pageFixture({ nextCursor: null }),
      items: [eventFixture(), { ...eventFixture(), id: 'event-2', eventType: 'READY' }],
    };
    const { fixture, api } = await configure({ page: first });
    api.listInbox.mockReturnValueOnce(of(second));

    buttonByText(fixture, '加载更多').click();
    fixture.detectChanges();

    expect(api.listInbox).toHaveBeenLastCalledWith({ limit: 50, cursor: 'next' });
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('READY');
  });

  it('clears a superseded load-more state when filters change', async () => {
    const pendingPage = new Subject<InboxEventPage>();
    const { fixture, api } = await configure({
      page: pageFixture({ nextCursor: 'next', hasMore: true }),
    });
    api.listInbox.mockReturnValueOnce(pendingPage.asObservable());

    buttonByText(fixture, '加载更多').click();
    fixture.detectChanges();
    expect(buttonByText(fixture, '正在加载').disabled).toBe(true);

    const statusSelect = element<HTMLSelectElement>(fixture, 'select[aria-label="按状态筛选"]');
    statusSelect.value = 'PROCESSING';
    statusSelect.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    expect(buttonByText(fixture, '加载更多').disabled).toBe(false);
    pendingPage.next(pageFixture({ nextCursor: null, hasMore: false }));
    pendingPage.complete();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Support Bot');
  });

  it('does not collapse expanded pages during background polling', async () => {
    vi.useFakeTimers();
    try {
      const firstItems = Array.from({ length: 50 }, (_, index) => ({
        ...eventFixture(),
        id: `event-${index}`,
        platformEventId: `platform-${index}`,
      }));
      const { fixture, api } = await configure({
        page: pageFixture({ items: firstItems, nextCursor: 'next', hasMore: true }),
      });
      api.listInbox.mockReturnValueOnce(
        of(pageFixture({
          items: [{ ...eventFixture(), id: 'event-50', platformEventId: 'platform-50' }],
        })),
      );

      buttonByText(fixture, '加载更多').click();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(51);
      expect(api.listInbox).toHaveBeenCalledTimes(2);

      await vi.advanceTimersByTimeAsync(5000);
      fixture.detectChanges();
      expect(api.listInbox).toHaveBeenCalledTimes(2);
      expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(51);
    } finally {
      vi.useRealTimers();
    }
  });

  it('refreshes queue badges while polling the Inbox view', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, api } = await configure();
      expect(api.getOutboxStats).toHaveBeenCalledTimes(1);
      expect(api.getDlqStats).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(5000);
      fixture.detectChanges();

      expect(api.getOutboxStats).toHaveBeenCalledTimes(2);
      expect(api.getDlqStats).toHaveBeenCalledTimes(2);
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('loads detail payload and renders it as escaped text', async () => {
    const detail = detailFixture('{"content":"<img src=x onerror=alert(1)>"}');
    const { fixture, api } = await configure({ detail });

    buttonByLabel(fixture, '查看事件详情').click();
    fixture.detectChanges();

    expect(api.getInboxEvent).toHaveBeenCalledWith('event-1');
    expect(fixture.nativeElement.querySelector('[role="dialog"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.payload-view').textContent).toContain(
      '<img src=x onerror=alert(1)>',
    );
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
  });

  it('moves focus into the detail dialog and restores the trigger on close', async () => {
    const { fixture } = await configure();
    const trigger = buttonByLabel(fixture, '查看事件详情');
    trigger.focus();
    trigger.click();
    fixture.detectChanges();
    await Promise.resolve();

    expect((document.activeElement as HTMLElement).getAttribute('aria-label')).toBe('关闭事件详情');

    buttonByLabel(fixture, '关闭事件详情').click();
    fixture.detectChanges();
    await Promise.resolve();
    expect(document.activeElement).toBe(trigger);
  });

  it('falls back to the original payload when it is not valid JSON', async () => {
    const { fixture } = await configure({ detail: detailFixture('not-json') });

    buttonByLabel(fixture, '查看事件详情').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.payload-view').textContent).toContain('not-json');
  });

  it('warns when the detail payload was truncated by the API', async () => {
    const { fixture } = await configure({
      detail: { ...detailFixture('{"partial":true}'), payloadTruncated: true },
    });

    buttonByLabel(fixture, '查看事件详情').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('当前内容已截断');
  });

  it('loads Outbox and DLQ jobs from their APIs', async () => {
    const { fixture, api } = await configure({
      outboxPage: outboxPageFixture(),
      dlqPage: outboxPageFixture({
        items: [{ ...outboxJobFixture(), id: 'dead-1', status: 'DEAD_LETTER', jobType: 'SEND_MESSAGE' }],
      }),
    });

    buttonByText(fixture, 'Outbox').click();
    fixture.detectChanges();
    expect(api.listOutbox).toHaveBeenCalledWith({ limit: 50 });
    expect(fixture.nativeElement.textContent).toContain('SEND_MESSAGE');
    expect(fixture.nativeElement.textContent).toContain('待处理');

    buttonByText(fixture, 'DLQ').click();
    fixture.detectChanges();
    expect(api.listDlq).toHaveBeenCalledWith({ limit: 50 });
    expect(fixture.nativeElement.textContent).toContain('死信');
  });

  it('renders queue badges from the independent statistics APIs before opening their tabs', async () => {
    const { fixture, api } = await configure({
      outboxStats: queueStats({ pendingCount: 2, retryWaitCount: 1, totalCount: 4 }),
      dlqStats: queueStats({ pendingCount: 0, deadLetterCount: 3, totalCount: 3 }),
    });

    expect(buttonByText(fixture, 'Outbox').textContent).toContain('3');
    expect(buttonByText(fixture, 'DLQ').textContent).toContain('3');
    expect(api.listOutbox).not.toHaveBeenCalled();
    expect(api.listDlq).not.toHaveBeenCalled();
  });

  it('loads Outbox task detail payload', async () => {
    const { fixture, api } = await configure({
      outboxPage: outboxPageFixture(),
      outboxDetail: outboxDetailFixture('{"content":"<b>safe text</b>"}'),
    });
    buttonByText(fixture, 'Outbox').click();
    fixture.detectChanges();
    buttonByLabel(fixture, '查看任务详情').click();
    fixture.detectChanges();
    expect(api.getOutboxJob).toHaveBeenCalledWith('job-1');
    expect(fixture.nativeElement.querySelector('.payload-view').textContent).toContain(
      '<b>safe text</b>',
    );
    expect(fixture.nativeElement.querySelector('b')).toBeNull();
  });

  it('sends Outbox filters and prevents an invalid DLQ status filter', async () => {
    const { fixture, api } = await configure();
    buttonByText(fixture, 'Outbox').click();
    fixture.detectChanges();

    const status = element<HTMLSelectElement>(fixture, 'select[aria-label="按任务状态筛选"]');
    status.value = 'RETRY_WAIT';
    status.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    expect(api.listOutbox).toHaveBeenLastCalledWith({ limit: 50, status: 'RETRY_WAIT' });

    buttonByText(fixture, 'DLQ').click();
    fixture.detectChanges();
    expect(api.listDlq).toHaveBeenLastCalledWith({ limit: 50 });
    expect(fixture.nativeElement.querySelector('select[aria-label="按任务状态筛选"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('状态：死信');
  });
});

interface ConfigureOptions {
  page?: InboxEventPage;
  listError?: unknown;
  detail?: InboxEventDetail;
  detailError?: unknown;
  outboxPage?: OutboxJobPage;
  dlqPage?: OutboxJobPage;
  outboxDetail?: OutboxJobDetail;
  dlqDetail?: OutboxJobDetail;
  outboxStats?: OutboxQueueStats;
  dlqStats?: OutboxQueueStats;
}

async function configure(options: ConfigureOptions = {}): Promise<{
  fixture: ComponentFixture<EventsPage>;
  api: {
    listInbox: ReturnType<typeof vi.fn>;
    getInboxEvent: ReturnType<typeof vi.fn>;
    listOutbox: ReturnType<typeof vi.fn>;
    listDlq: ReturnType<typeof vi.fn>;
    getOutboxJob: ReturnType<typeof vi.fn>;
    getDlqJob: ReturnType<typeof vi.fn>;
    getOutboxStats: ReturnType<typeof vi.fn>;
    getDlqStats: ReturnType<typeof vi.fn>;
  };
}> {
  const api = {
    listInbox: options.listError
      ? vi.fn((_query: InboxQuery) => throwError(() => options.listError))
      : vi.fn((_query: InboxQuery) => of(options.page ?? pageFixture())),
    getInboxEvent: options.detailError
      ? vi.fn((_id: string) => throwError(() => options.detailError))
      : vi.fn((_id: string) => of(options.detail ?? detailFixture('{"id":"event-1"}'))),
    listOutbox: vi.fn((_query: OutboxQuery) => of(options.outboxPage ?? outboxPageFixture())),
    listDlq: vi.fn((_query: OutboxQuery) =>
      of(options.dlqPage ?? outboxPageFixture({ items: [] })),
    ),
    getOutboxJob: vi.fn((_id: string) =>
      of(options.outboxDetail ?? outboxDetailFixture('{"id":"job-1"}')),
    ),
    getDlqJob: vi.fn((_id: string) =>
      of(options.dlqDetail ?? outboxDetailFixture('{"id":"job-1"}')),
    ),
    getOutboxStats: vi.fn(() => of(options.outboxStats ?? queueStats())),
    getDlqStats: vi.fn(() =>
      of(options.dlqStats ?? queueStats({ totalCount: 0, pendingCount: 0 })),
    ),
  };
  await TestBed.configureTestingModule({
    imports: [EventsPage],
    providers: [{ provide: EventApiService, useValue: api }],
  }).compileComponents();
  const fixture = TestBed.createComponent(EventsPage);
  fixture.detectChanges();
  return { fixture, api };
}

function pageFixture(overrides: Partial<InboxEventPage> = {}): InboxEventPage {
  return {
    items: [eventFixture()],
    nextCursor: null,
    hasMore: false,
    observedAt: '2026-07-18T12:00:00Z',
    ...overrides,
  };
}

function emptyPage(): InboxEventPage {
  return {
    items: [],
    nextCursor: null,
    hasMore: false,
    observedAt: '2026-07-18T12:00:00Z',
  };
}

function eventFixture(): InboxEventSummary {
  return {
    id: 'event-1',
    botId: 'bot-1',
    botDisplayName: 'Support Bot',
    appId: '10001',
    environment: 'SANDBOX',
    eventType: 'MESSAGE_CREATE',
    platformEventId: 'platform-1',
    status: 'RECEIVED',
    attempt: 0,
    availableAt: '2026-07-18T12:00:00Z',
    lastError: null,
    receivedAt: '2026-07-18T12:00:00Z',
    updatedAt: '2026-07-18T12:00:00Z',
  };
}

function detailFixture(payload: string): InboxEventDetail {
  return {
    ...eventFixture(),
    payload,
    payloadTruncated: false,
  };
}

function outboxPageFixture(overrides: Partial<OutboxJobPage> = {}): OutboxJobPage {
  return {
    items: [outboxJobFixture()],
    nextCursor: null,
    hasMore: false,
    observedAt: '2026-07-18T12:00:00Z',
    stats: queueStats(),
    ...overrides,
  };
}

function queueStats(overrides: Partial<OutboxQueueStats> = {}): OutboxQueueStats {
  return {
    totalCount: 1,
    pendingCount: 1,
    inProgressCount: 0,
    retryWaitCount: 0,
    succeededCount: 0,
    resultUnknownCount: 0,
    deadLetterCount: 0,
    ...overrides,
  };
}

function outboxJobFixture(): OutboxJobSummary {
  return {
    id: 'job-1',
    environment: 'SANDBOX',
    botId: 'bot-1',
    botDisplayName: 'Support Bot',
    appId: '10001',
    jobType: 'SEND_MESSAGE',
    sourceEventId: 'event-1',
    status: 'PENDING',
    attempt: 0,
    availableAt: '2026-07-18T12:00:00Z',
    createdAt: '2026-07-18T12:00:00Z',
    updatedAt: '2026-07-18T12:00:00Z',
    completedAt: null,
    lastError: null,
  };
}

function outboxDetailFixture(payload: string): OutboxJobDetail {
  return {
    ...outboxJobFixture(),
    dedupKey: null,
    leaseUntil: null,
    payload,
    payloadTruncated: false,
  };
}

function element<T extends HTMLElement = HTMLElement>(
  fixture: ComponentFixture<EventsPage>,
  selector: string,
): T {
  const result = fixture.nativeElement.querySelector(selector) as T | null;
  if (!result) {
    throw new Error(`Missing element: ${selector}`);
  }
  return result;
}

function buttonByText(fixture: ComponentFixture<EventsPage>, label: string): HTMLButtonElement {
  const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
  const button = Array.from(buttons).find((candidate) => candidate.textContent?.includes(label));
  if (!button) {
    throw new Error(`Missing button: ${label}`);
  }
  return button;
}

function buttonByLabel(fixture: ComponentFixture<EventsPage>, label: string): HTMLButtonElement {
  return element<HTMLButtonElement>(fixture, `button[aria-label="${label}"]`);
}
