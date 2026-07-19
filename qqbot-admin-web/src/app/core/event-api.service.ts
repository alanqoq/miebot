import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type InboxEnvironment = 'SANDBOX' | 'PRODUCTION';

export type InboxStatus =
  | 'RECEIVED'
  | 'PROCESSING'
  | 'DISPATCHED'
  | 'DEAD_LETTER';

export type OutboxStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'RESULT_UNKNOWN'
  | 'DEAD_LETTER';

export interface InboxQuery {
  limit?: number;
  cursor?: string | null;
  query?: string;
  botId?: string;
  environment?: InboxEnvironment;
  status?: InboxStatus;
  eventType?: string;
}

export interface InboxEventSummary {
  id: string;
  botId: string;
  botDisplayName: string | null;
  appId: string | null;
  environment: InboxEnvironment;
  eventType: string;
  platformEventId: string;
  status: InboxStatus;
  attempt: number;
  availableAt: string;
  lastError: string | null;
  receivedAt: string;
  updatedAt: string;
}

export interface InboxEventPage {
  items: InboxEventSummary[];
  nextCursor: string | null;
  hasMore: boolean;
  observedAt: string;
}

export interface InboxEventDetail extends InboxEventSummary {
  payload: string;
  payloadTruncated: boolean;
}

export interface OutboxQuery {
  limit?: number;
  cursor?: string | null;
  query?: string;
  botId?: string;
  environment?: InboxEnvironment;
  status?: OutboxStatus;
  jobType?: string;
}

export interface OutboxJobSummary {
  id: string;
  environment: InboxEnvironment;
  botId: string;
  botDisplayName: string | null;
  appId: string | null;
  jobType: string;
  sourceEventId: string | null;
  status: OutboxStatus;
  attempt: number;
  availableAt: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  lastError: string | null;
}

export interface OutboxJobPage {
  items: OutboxJobSummary[];
  nextCursor: string | null;
  hasMore: boolean;
  observedAt: string;
  stats: OutboxQueueStats;
}

export interface OutboxQueueStats {
  totalCount: number;
  pendingCount: number;
  inProgressCount: number;
  retryWaitCount: number;
  succeededCount: number;
  resultUnknownCount: number;
  deadLetterCount: number;
}

export interface OutboxJobDetail extends OutboxJobSummary {
  dedupKey: string | null;
  leaseUntil: string | null;
  payload: string;
  payloadTruncated: boolean;
}

export type PluginDeliveryStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'DEAD_LETTER'
  | 'PAUSED';

export interface PluginDeliverySummary {
  id: string;
  eventId: string;
  bindingId: string;
  pluginId: string | null;
  botId: string | null;
  botDisplayName: string | null;
  handlerId: string;
  status: PluginDeliveryStatus;
  attempt: number;
  availableAt: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  lastError: string | null;
}

export interface PluginDeliveryQueueStats {
  totalCount: number;
  pendingCount: number;
  inProgressCount: number;
  retryWaitCount: number;
  succeededCount: number;
  deadLetterCount: number;
  pausedCount: number;
}

export interface PluginDeliveryPage {
  items: PluginDeliverySummary[];
  nextCursor: string | null;
  hasMore: boolean;
  observedAt: string;
  stats: PluginDeliveryQueueStats;
}

export interface PluginDeliveryQuery {
  limit?: number;
  cursor?: string | null;
  query?: string;
  bindingId?: string;
  status?: PluginDeliveryStatus;
}

export interface PluginDeliveryDetail extends PluginDeliverySummary {
  leaseUntil: string | null;
  eventType: string;
  platformEventId: string;
  eventReceivedAt: string;
}

@Injectable({ providedIn: 'root' })
export class EventApiService {
  private readonly http = inject(HttpClient);
  private readonly resourceUrl = '/api/events/inbox';

  listInbox(query: InboxQuery = {}): Observable<InboxEventPage> {
    let params = new HttpParams();
    if (query.limit !== undefined) {
      params = params.set('limit', query.limit);
    }
    if (query.cursor) {
      params = params.set('cursor', query.cursor);
    }
    if (query.query?.trim()) {
      params = params.set('query', query.query.trim());
    }
    if (query.botId?.trim()) {
      params = params.set('botId', query.botId.trim());
    }
    if (query.environment) {
      params = params.set('environment', query.environment);
    }
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.eventType?.trim()) {
      params = params.set('eventType', query.eventType.trim());
    }
    return this.http.get<InboxEventPage>(this.resourceUrl, { params });
  }

  getInboxEvent(id: string): Observable<InboxEventDetail> {
    return this.http.get<InboxEventDetail>(
      `${this.resourceUrl}/${encodeURIComponent(id)}`,
    );
  }

  listOutbox(query: OutboxQuery = {}): Observable<OutboxJobPage> {
    return this.http.get<OutboxJobPage>('/api/events/outbox', {
      params: this.deliveryParams(query),
    });
  }

  listDlq(query: OutboxQuery = {}): Observable<OutboxJobPage> {
    return this.http.get<OutboxJobPage>('/api/events/dlq', {
      params: this.deliveryParams(query),
    });
  }

  getOutboxStats(): Observable<OutboxQueueStats> {
    return this.http.get<OutboxQueueStats>('/api/events/outbox/stats');
  }

  getDlqStats(): Observable<OutboxQueueStats> {
    return this.http.get<OutboxQueueStats>('/api/events/dlq/stats');
  }

  getOutboxJob(id: string): Observable<OutboxJobDetail> {
    return this.http.get<OutboxJobDetail>(
      `/api/events/outbox/${encodeURIComponent(id)}`,
    );
  }

  getDlqJob(id: string): Observable<OutboxJobDetail> {
    return this.http.get<OutboxJobDetail>(
      `/api/events/dlq/${encodeURIComponent(id)}`,
    );
  }

  listPluginDeliveries(query: PluginDeliveryQuery = {}): Observable<PluginDeliveryPage> {
    return this.http.get<PluginDeliveryPage>('/api/events/plugin-deliveries', {
      params: this.pluginDeliveryParams(query),
    });
  }

  listPluginDlq(query: PluginDeliveryQuery = {}): Observable<PluginDeliveryPage> {
    return this.http.get<PluginDeliveryPage>('/api/events/plugin-dlq', {
      params: this.pluginDeliveryParams(query),
    });
  }

  getPluginDeliveryStats(): Observable<PluginDeliveryQueueStats> {
    return this.http.get<PluginDeliveryQueueStats>('/api/events/plugin-deliveries/stats');
  }

  getPluginDelivery(id: string, deadLetterOnly = false): Observable<PluginDeliveryDetail> {
    const resource = deadLetterOnly ? '/api/events/plugin-dlq' : '/api/events/plugin-deliveries';
    return this.http.get<PluginDeliveryDetail>(`${resource}/${encodeURIComponent(id)}`);
  }

  private deliveryParams(query: OutboxQuery): HttpParams {
    let params = new HttpParams();
    if (query.limit !== undefined) {
      params = params.set('limit', query.limit);
    }
    if (query.cursor) {
      params = params.set('cursor', query.cursor);
    }
    if (query.query?.trim()) {
      params = params.set('query', query.query.trim());
    }
    if (query.botId?.trim()) {
      params = params.set('botId', query.botId.trim());
    }
    if (query.environment) {
      params = params.set('environment', query.environment);
    }
    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.jobType?.trim()) {
      params = params.set('jobType', query.jobType.trim());
    }
    return params;
  }

  private pluginDeliveryParams(query: PluginDeliveryQuery): HttpParams {
    let params = new HttpParams();
    if (query.limit !== undefined) params = params.set('limit', query.limit);
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.query?.trim()) params = params.set('query', query.query.trim());
    if (query.bindingId?.trim()) params = params.set('bindingId', query.bindingId.trim());
    if (query.status) params = params.set('status', query.status);
    return params;
  }
}
