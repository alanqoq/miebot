import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { finalize, shareReplay, tap } from 'rxjs/operators';
import { DatabaseStatusStore } from './database-status.store';

export interface SystemInfo {
  service: string;
  version: string;
  status: string;
  database: string;
  serverTime: string;
}

export interface HealthStatus {
  status: string;
  checkedAt: string;
  components: Record<string, string>;
}

export type DatabaseType = 'SQLITE' | 'MYSQL' | 'POSTGRESQL';
export type DatabaseSslMode =
  | 'DISABLED'
  | 'PREFERRED'
  | 'REQUIRED'
  | 'VERIFY_CA'
  | 'VERIFY_IDENTITY';
export type DatabaseSchemaState = 'EMPTY' | 'INITIALIZED' | 'READY';

export interface DatabaseConfiguration {
  revision: number;
  type: DatabaseType;
  sqlitePath: string | null;
  busyTimeoutMs: number | null;
  host: string | null;
  port: number | null;
  databaseName: string | null;
  username: string | null;
  sslMode: DatabaseSslMode | null;
  connectTimeoutMs: number | null;
  passwordConfigured: boolean;
  databaseProduct: string | null;
  databaseVersion: string | null;
  switchInProgress: boolean;
  lastSwitchedAt: string | null;
}

export interface DatabaseCandidate {
  type: DatabaseType;
  sqlitePath?: string | null;
  busyTimeoutMs?: number | null;
  host?: string | null;
  port?: number | null;
  databaseName?: string | null;
  username?: string | null;
  password?: string;
  sslMode?: DatabaseSslMode | null;
  connectTimeoutMs?: number | null;
}

export interface DatabaseTestResult {
  success: boolean;
  type: DatabaseType;
  databaseProduct: string;
  databaseVersion: string;
  latencyMs: number;
  readVerified: boolean;
  writeVerified: boolean;
  schemaState: DatabaseSchemaState;
  schemaVersion: string | null;
  initialized: boolean;
  adminSeeded: boolean;
  botCount: number;
  testedAt: string;
}

export interface DatabaseSwitchResult {
  configuration: DatabaseConfiguration;
  verification: DatabaseTestResult;
}

export interface AuditLogEntry {
  id: string;
  actorUsername: string | null;
  action: string;
  resourcePath: string;
  outcomeStatus: number;
  remoteAddress: string | null;
  traceId: string | null;
  createdAt: string;
}

export interface AuditLogPage {
  items: AuditLogEntry[];
  nextCursor: string | null;
  hasMore: boolean;
  observedAt: string;
}

@Injectable({ providedIn: 'root' })
export class SystemApiService {
  private readonly http = inject(HttpClient);
  private readonly databaseStatus = inject(DatabaseStatusStore);
  private databaseConfigurationRequest: Observable<DatabaseConfiguration> | null = null;

  getSystemInfo(): Observable<SystemInfo> {
    return this.http.get<SystemInfo>('/api/system/info');
  }

  getLiveness(): Observable<HealthStatus> {
    return this.http.get<HealthStatus>('/health/live');
  }

  getReadiness(): Observable<HealthStatus> {
    return this.http.get<HealthStatus>('/health/ready');
  }

  getDatabaseConfiguration(): Observable<DatabaseConfiguration> {
    return this.http
      .get<DatabaseConfiguration>('/api/system/database/configuration')
      .pipe(tap((configuration) => this.databaseStatus.setConfiguration(configuration)));
  }

  ensureDatabaseConfiguration(): Observable<DatabaseConfiguration> {
    const current = this.databaseStatus.configuration();
    if (current) {
      return of(current);
    }
    if (this.databaseConfigurationRequest) {
      return this.databaseConfigurationRequest;
    }
    const request = this.getDatabaseConfiguration().pipe(
      finalize(() => {
        this.databaseConfigurationRequest = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    this.databaseConfigurationRequest = request;
    return request;
  }

  testDatabase(candidate: DatabaseCandidate): Observable<DatabaseTestResult> {
    return this.http.post<DatabaseTestResult>('/api/system/database/test', candidate);
  }

  switchDatabase(
    expectedRevision: number,
    candidate: DatabaseCandidate
  ): Observable<DatabaseSwitchResult> {
    return this.http
      .post<DatabaseSwitchResult>('/api/system/database/switch', {
        expectedRevision,
        candidate
      })
      .pipe(tap((result) => this.databaseStatus.setConfiguration(result.configuration)));
  }

  reloadDatabase(expectedRevision: number): Observable<DatabaseSwitchResult> {
    return this.http
      .post<DatabaseSwitchResult>('/api/system/database/reload', { expectedRevision })
      .pipe(tap((result) => this.databaseStatus.setConfiguration(result.configuration)));
  }

  listAuditLogs(limit = 20, cursor?: string | null): Observable<AuditLogPage> {
    const params: Record<string, string | number> = { limit };
    if (cursor) params['cursor'] = cursor;
    return this.http.get<AuditLogPage>('/api/audit-logs', { params });
  }
}
