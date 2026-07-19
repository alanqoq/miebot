import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  DatabaseCandidate,
  DatabaseConfiguration,
  DatabaseSwitchResult,
  DatabaseTestResult,
  SystemApiService
} from './system-api.service';

describe('SystemApiService', () => {
  let api: SystemApiService;
  let http: HttpTestingController;

  const configuration: DatabaseConfiguration = {
    revision: 4,
    type: 'POSTGRESQL',
    sqlitePath: null,
    busyTimeoutMs: null,
    host: 'postgres.internal',
    port: 5432,
    databaseName: 'qqbot',
    username: 'qqbot_app',
    sslMode: 'PREFERRED',
    connectTimeoutMs: 5000,
    passwordConfigured: true,
    databaseProduct: 'PostgreSQL',
    databaseVersion: '15.8',
    switchInProgress: false,
    lastSwitchedAt: '2026-07-17T04:00:00Z'
  };
  const verification: DatabaseTestResult = {
    success: true,
    type: 'POSTGRESQL',
    databaseProduct: 'PostgreSQL',
    databaseVersion: '15.8',
    latencyMs: 16,
    readVerified: true,
    writeVerified: true,
    schemaState: 'READY',
    schemaVersion: '4',
    initialized: false,
    adminSeeded: false,
    botCount: 2,
    testedAt: '2026-07-17T04:00:00Z'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(SystemApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the process liveness probe', () => {
    api.getLiveness().subscribe((value) => expect(value.components['process']).toBe('UP'));

    const pending = http.expectOne('/health/live');
    expect(pending.request.method).toBe('GET');
    pending.flush({
      status: 'UP',
      checkedAt: '2026-07-18T12:00:00Z',
      components: { process: 'UP' },
    });
  });

  it('loads and caches the sanitized database configuration', () => {
    let first: DatabaseConfiguration | undefined;
    let second: DatabaseConfiguration | undefined;

    api.ensureDatabaseConfiguration().subscribe((value) => (first = value));
    const pending = http.expectOne('/api/system/database/configuration');
    expect(pending.request.method).toBe('GET');
    pending.flush(configuration);

    api.ensureDatabaseConfiguration().subscribe((value) => (second = value));
    http.expectNone('/api/system/database/configuration');
    expect(first).toEqual(configuration);
    expect(second).toEqual(configuration);
  });

  it('tests a write-only database password without changing active state', () => {
    const candidate: DatabaseCandidate = {
      type: 'POSTGRESQL',
      host: 'postgres.internal',
      port: 5432,
      databaseName: 'qqbot',
      username: 'qqbot_app',
      password: 'write-only-password',
      sslMode: 'PREFERRED',
      connectTimeoutMs: 5000
    };

    api.testDatabase(candidate).subscribe();

    const pending = http.expectOne('/api/system/database/test');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual(candidate);
    pending.flush(verification);
  });

  it('switches and publishes the new active configuration', () => {
    const candidate: DatabaseCandidate = {
      type: 'POSTGRESQL',
      host: 'postgres.internal',
      port: 5432,
      databaseName: 'qqbot',
      username: 'qqbot_app',
      sslMode: 'PREFERRED',
      connectTimeoutMs: 5000
    };
    const result: DatabaseSwitchResult = { configuration, verification };

    api.switchDatabase(3, candidate).subscribe();

    const pending = http.expectOne('/api/system/database/switch');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual({ expectedRevision: 3, candidate });
    pending.flush(result);

    api.ensureDatabaseConfiguration().subscribe((value) => expect(value).toEqual(configuration));
    http.expectNone('/api/system/database/configuration');
  });

  it('reloads the mounted configuration using the current revision', () => {
    api.reloadDatabase(4).subscribe();

    const pending = http.expectOne('/api/system/database/reload');
    expect(pending.request.method).toBe('POST');
    expect(pending.request.body).toEqual({ expectedRevision: 4 });
    pending.flush({ configuration, verification } satisfies DatabaseSwitchResult);
  });
});
