import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type BotEnvironment = 'SANDBOX' | 'PRODUCTION';
export type MessageTargetType = 'C2C' | 'GROUP' | 'CHANNEL' | 'DIRECT';
export type BotMessageKind = 'TEXT' | 'MEDIA' | 'MARKDOWN' | 'KEYBOARD' | 'ARK' | 'EMBED';
export type MediaKind = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'FILE';

export type BotRuntimeState =
  | 'DISABLED'
  | 'STARTING'
  | 'DISCOVERING'
  | 'CONNECTING'
  | 'AUTHENTICATING'
  | 'READY'
  | 'ONLINE'
  | 'RECONNECTING'
  | 'STOPPING'
  | 'FAILED'
  | 'STOPPED';

export interface BotRuntimeSession {
  id: string;
  sequence: number;
}

export interface BotRuntimeError {
  code: string;
  message: string;
  occurredAt: string;
}

export interface BotRuntimeStatus {
  botId: string;
  configurationRevision: number;
  enabled: boolean;
  state: BotRuntimeState;
  stateChangedAt: string;
  connectedAt: string | null;
  lastHeartbeatAt: string | null;
  lastDispatchAt: string | null;
  reconnectCount: number;
  session: BotRuntimeSession | null;
  lastError: BotRuntimeError | null;
}

export interface BotRuntimeSummary {
  totalCount: number;
  enabledCount: number;
  connectedCount: number;
  observedAt: string;
  bots: BotRuntimeStatus[];
}

export interface BotConfiguration {
  id: string;
  displayName: string;
  appId: string;
  environment: BotEnvironment;
  intents: number;
  shardIndex: number;
  shardCount: number;
  enabled: boolean;
  revision: number;
  createdAt: string;
  updatedAt: string;
  secretConfigured: boolean;
  /** Stored and exchanged in bytes; older API responses may omit it. */
  maxMediaUploadBytes?: number;
}

export interface CreateBotRequest {
  displayName: string;
  appId: string;
  environment: BotEnvironment;
  intents: number;
  shardIndex: number;
  shardCount: number;
  enabled: boolean;
  appSecret: string;
  maxMediaUploadBytes?: number;
}

export interface UpdateBotRequest {
  expectedRevision: number;
  displayName: string;
  appId: string;
  environment: BotEnvironment;
  intents: number;
  shardIndex: number;
  shardCount: number;
  appSecret?: string;
  maxMediaUploadBytes?: number;
}

export interface SetBotEnabledRequest {
  expectedRevision: number;
  enabled: boolean;
}

export interface MediaUploadResponse {
  id: string;
  botId: string;
  kind: MediaKind;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

export interface SendBotMessageRequest {
  kind: BotMessageKind;
  targetType: MessageTargetType;
  targetId: string;
  content?: string;
  mediaKind?: MediaKind;
  mediaUrl?: string;
  mediaAssetId?: string;
  payload?: Record<string, unknown>;
  replyMessageId?: string;
  replyEventId?: string;
  messageSequence: number;
}

export interface BotMessageResponse {
  jobId: string;
  alreadyPresent: boolean;
  queuedAt: string;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  fieldErrors: Record<string, string>;
  traceId: string;
  timestamp: string;
}

@Injectable({ providedIn: 'root' })
export class BotApiService {
  private readonly http = inject(HttpClient);
  private readonly resourceUrl = '/api/bots';

  list(): Observable<BotConfiguration[]> {
    return this.http.get<BotConfiguration[]>(this.resourceUrl);
  }

  getRuntimeSummary(): Observable<BotRuntimeSummary> {
    return this.http.get<BotRuntimeSummary>(`${this.resourceUrl}/runtime`);
  }

  observeRuntime(): Observable<BotRuntimeSummary> {
    return new Observable<BotRuntimeSummary>((subscriber) => {
      if (typeof EventSource === 'undefined') {
        subscriber.complete();
        return;
      }
      const source = new EventSource(`${this.resourceUrl}/runtime/stream`);
      const runtime = (event: MessageEvent<string>) => {
        try {
          subscriber.next(JSON.parse(event.data) as BotRuntimeSummary);
        } catch (error) {
          subscriber.error(error);
        }
      };
      source.addEventListener('runtime', runtime as EventListener);
      source.onerror = () => {
        source.close();
        subscriber.complete();
      };
      return () => source.close();
    });
  }

  get(id: string): Observable<BotConfiguration> {
    return this.http.get<BotConfiguration>(`${this.resourceUrl}/${encodeURIComponent(id)}`);
  }

  create(request: CreateBotRequest): Observable<BotConfiguration> {
    return this.http.post<BotConfiguration>(this.resourceUrl, request);
  }

  update(id: string, request: UpdateBotRequest): Observable<BotConfiguration> {
    return this.http.put<BotConfiguration>(
      `${this.resourceUrl}/${encodeURIComponent(id)}`,
      request,
    );
  }

  setEnabled(id: string, request: SetBotEnabledRequest): Observable<BotConfiguration> {
    return this.http.patch<BotConfiguration>(
      `${this.resourceUrl}/${encodeURIComponent(id)}/enabled`,
      request,
    );
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.resourceUrl}/${encodeURIComponent(id)}`);
  }

  uploadMedia(id: string, kind: MediaKind, file: File): Observable<MediaUploadResponse> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<MediaUploadResponse>(
      `${this.resourceUrl}/${encodeURIComponent(id)}/media?kind=${encodeURIComponent(kind)}`,
      form,
    );
  }

  sendMessage(id: string, request: SendBotMessageRequest): Observable<BotMessageResponse> {
    return this.http.post<BotMessageResponse>(
      `${this.resourceUrl}/${encodeURIComponent(id)}/messages`,
      request,
    );
  }
}
