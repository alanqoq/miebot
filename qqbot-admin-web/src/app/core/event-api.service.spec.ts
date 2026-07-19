import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  EventApiService,
  InboxEventDetail,
  InboxEventPage,
  InboxStatus,
  OutboxJobPage,
} from './event-api.service';

describe('EventApiService', () => {
  let api: EventApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(EventApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests a filtered Inbox page with encoded query parameters', () => {
    const page: InboxEventPage = {
      items: [],
      nextCursor: null,
      hasMore: false,
      observedAt: '2026-07-18T12:00:00Z',
    };
    let result: InboxEventPage | undefined;

    api
      .listInbox({
        limit: 25,
        cursor: 'cursor / 1',
        query: 'message create',
        botId: 'bot-1',
        environment: 'PRODUCTION',
        status: 'RECEIVED',
        eventType: 'MESSAGE_CREATE',
      })
      .subscribe((value) => (result = value));

    const request = http.expectOne((candidate) => candidate.url === '/api/events/inbox');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('limit')).toBe('25');
    expect(request.request.params.get('cursor')).toBe('cursor / 1');
    expect(request.request.params.get('query')).toBe('message create');
    expect(request.request.params.get('botId')).toBe('bot-1');
    expect(request.request.params.get('environment')).toBe('PRODUCTION');
    expect(request.request.params.get('status')).toBe('RECEIVED');
    expect(request.request.params.get('eventType')).toBe('MESSAGE_CREATE');
    request.flush(page);

    expect(result).toEqual(page);
  });

  it('omits empty optional query values', () => {
    api.listInbox({ query: '  ', cursor: null }).subscribe();

    const request = http.expectOne('/api/events/inbox');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ items: [], nextCursor: null, hasMore: false, observedAt: '' });
  });

  it('loads a detail payload by encoded event id', () => {
    const detail = detailFixture();
    let result: InboxEventDetail | undefined;

    api.getInboxEvent('event/id').subscribe((value) => (result = value));

    const request = http.expectOne('/api/events/inbox/event%2Fid');
    expect(request.request.method).toBe('GET');
    request.flush(detail);

    expect(result).toEqual(detail);
  });

  it('requests filtered Outbox and fixed DLQ resources', () => {
    const page: OutboxJobPage = {
      items: [],
      nextCursor: null,
      hasMore: false,
      observedAt: '2026-07-18T12:00:00Z',
      stats: {
        totalCount: 0,
        pendingCount: 0,
        inProgressCount: 0,
        retryWaitCount: 0,
        succeededCount: 0,
        resultUnknownCount: 0,
        deadLetterCount: 0,
      },
    };

    api.listOutbox({
      limit: 50,
      cursor: 'next page',
      query: 'send message',
      botId: 'bot-1',
      environment: 'SANDBOX',
      status: 'RETRY_WAIT',
      jobType: 'SEND_MESSAGE',
    }).subscribe();
    const outbox = http.expectOne((request) => request.url === '/api/events/outbox');
    expect(outbox.request.params.get('limit')).toBe('50');
    expect(outbox.request.params.get('cursor')).toBe('next page');
    expect(outbox.request.params.get('query')).toBe('send message');
    expect(outbox.request.params.get('botId')).toBe('bot-1');
    expect(outbox.request.params.get('environment')).toBe('SANDBOX');
    expect(outbox.request.params.get('status')).toBe('RETRY_WAIT');
    expect(outbox.request.params.get('jobType')).toBe('SEND_MESSAGE');
    outbox.flush(page);

    api.listDlq({ limit: 25 }).subscribe();
    const dlq = http.expectOne('/api/events/dlq?limit=25');
    expect(dlq.request.method).toBe('GET');
    dlq.flush(page);
  });

  it('loads encoded Outbox and DLQ detail ids', () => {
    api.getOutboxJob('job/id').subscribe();
    const outbox = http.expectOne('/api/events/outbox/job%2Fid');
    expect(outbox.request.method).toBe('GET');
    outbox.flush({});

    api.getDlqJob('dead/id').subscribe();
    const dlq = http.expectOne('/api/events/dlq/dead%2Fid');
    expect(dlq.request.method).toBe('GET');
    dlq.flush({});
  });

  it('loads Outbox and DLQ statistics without fetching list rows', () => {
    const stats = {
      totalCount: 3,
      pendingCount: 1,
      inProgressCount: 0,
      retryWaitCount: 1,
      succeededCount: 0,
      resultUnknownCount: 0,
      deadLetterCount: 1,
    };

    api.getOutboxStats().subscribe((value) => expect(value).toEqual(stats));
    const outbox = http.expectOne('/api/events/outbox/stats');
    expect(outbox.request.method).toBe('GET');
    outbox.flush(stats);

    api.getDlqStats().subscribe((value) => expect(value).toEqual(stats));
    const dlq = http.expectOne('/api/events/dlq/stats');
    expect(dlq.request.method).toBe('GET');
    dlq.flush(stats);
  });
});

function detailFixture(): InboxEventDetail {
  return {
    id: 'event-1',
    botId: 'bot-1',
    botDisplayName: 'Support Bot',
    appId: '10001',
    environment: 'SANDBOX',
    eventType: 'MESSAGE_CREATE',
    platformEventId: 'platform-1',
    status: 'RECEIVED' as InboxStatus,
    attempt: 0,
    availableAt: '2026-07-18T12:00:00Z',
    lastError: null,
    receivedAt: '2026-07-18T12:00:00Z',
    updatedAt: '2026-07-18T12:00:00Z',
    payload: '{"id":"platform-1"}',
    payloadTruncated: false,
  };
}
