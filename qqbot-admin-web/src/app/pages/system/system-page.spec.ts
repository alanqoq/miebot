import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import {
  DatabaseCandidate,
  DatabaseConfiguration,
  DatabaseSwitchResult,
  DatabaseTestResult,
  SystemApiService
} from '../../core/system-api.service';
import { SystemPage } from './system-page';

describe('SystemPage', () => {
  const sqliteConfiguration: DatabaseConfiguration = {
    revision: 1,
    type: 'SQLITE',
    sqlitePath: '/data/qqbot.db',
    busyTimeoutMs: 5000,
    host: null,
    port: null,
    databaseName: null,
    username: null,
    sslMode: null,
    connectTimeoutMs: null,
    passwordConfigured: false,
    databaseProduct: 'SQLite',
    databaseVersion: '3.50.3',
    switchInProgress: false,
    lastSwitchedAt: null
  };
  const mysqlConfiguration: DatabaseConfiguration = {
    revision: 2,
    type: 'MYSQL',
    sqlitePath: null,
    busyTimeoutMs: null,
    host: 'mysql.internal',
    port: 3306,
    databaseName: 'qqbot',
    username: 'qqbot_app',
    sslMode: 'PREFERRED',
    connectTimeoutMs: 5000,
    passwordConfigured: true,
    databaseProduct: 'MySQL',
    databaseVersion: '8.4',
    switchInProgress: false,
    lastSwitchedAt: '2026-07-17T05:00:00Z'
  };
  const verification: DatabaseTestResult = {
    success: true,
    type: 'MYSQL',
    databaseProduct: 'MySQL',
    databaseVersion: '8.4',
    latencyMs: 12,
    readVerified: true,
    writeVerified: true,
    schemaState: 'EMPTY',
    schemaVersion: '4',
    initialized: true,
    adminSeeded: false,
    botCount: 0,
    testedAt: '2026-07-17T05:00:00Z'
  };

  const api = {
    ensureDatabaseConfiguration: vi.fn(() => of(sqliteConfiguration)),
    getDatabaseConfiguration: vi.fn(() => of(sqliteConfiguration)),
    testDatabase: vi.fn(() => of(verification)),
    switchDatabase: vi.fn(() =>
      of({ configuration: mysqlConfiguration, verification } satisfies DatabaseSwitchResult)
    ),
    reloadDatabase: vi.fn(() =>
      of({ configuration: mysqlConfiguration, verification } satisfies DatabaseSwitchResult)
    ),
    listAuditLogs: vi.fn(() => of({
      items: [], nextCursor: null, hasMore: false, observedAt: '2026-07-19T00:00:00Z'
    }))
  };

  beforeEach(async () => {
    for (const mock of Object.values(api)) {
      mock.mockClear();
    }
    await TestBed.configureTestingModule({
      imports: [SystemPage],
      providers: [{ provide: SystemApiService, useValue: api }]
    }).compileComponents();
  });

  it('requires a successful current-form test before switching databases', () => {
    const fixture = TestBed.createComponent(SystemPage);
    fixture.detectChanges();

    const mysqlButton = Array.from<HTMLButtonElement>(
      fixture.nativeElement.querySelectorAll('.database-type-control button')
    ).find((button) => button.textContent?.trim() === 'MySQL');
    mysqlButton?.click();
    fixture.detectChanges();

    setInput(fixture.nativeElement, 'host', 'mysql.internal');
    setInput(fixture.nativeElement, 'databaseName', 'qqbot');
    setInput(fixture.nativeElement, 'username', 'qqbot_app');
    setInput(fixture.nativeElement, 'password', 'write-only-password');
    fixture.detectChanges();

    const switchButton = fixture.nativeElement.querySelector(
      '.database-actions .button-primary'
    ) as HTMLButtonElement;
    expect(switchButton.disabled).toBe(true);

    const testButton = fixture.nativeElement.querySelector(
      '.database-actions .button-secondary'
    ) as HTMLButtonElement;
    testButton.click();
    fixture.detectChanges();

    const candidate: DatabaseCandidate = {
      type: 'MYSQL',
      host: 'mysql.internal',
      port: 3306,
      databaseName: 'qqbot',
      username: 'qqbot_app',
      password: 'write-only-password',
      sslMode: 'PREFERRED',
      connectTimeoutMs: 5000
    };
    expect(api.testDatabase).toHaveBeenCalledWith(candidate);
    expect(switchButton.disabled).toBe(false);

    switchButton.click();
    fixture.detectChanges();
    const confirmButton = fixture.nativeElement.querySelector(
      '.confirmation-strip .button-danger'
    ) as HTMLButtonElement;
    expect(confirmButton).toBeTruthy();
    confirmButton.click();
    fixture.detectChanges();

    expect(api.switchDatabase).toHaveBeenCalledWith(1, candidate);
    expect(fixture.nativeElement.textContent).toContain('已切换到 MySQL');
    expect((fixture.nativeElement.querySelector('input[formcontrolname="password"]') as HTMLInputElement).value)
      .toBe('');
  });

  it('loads the candidate file only after explicit confirmation', () => {
    const fixture = TestBed.createComponent(SystemPage);
    fixture.detectChanges();

    const reloadButton = Array.from<HTMLButtonElement>(
      fixture.nativeElement.querySelectorAll('.file-reload-row button')
    ).find((button) => button.textContent?.includes('从文件加载'));
    reloadButton?.click();
    fixture.detectChanges();

    expect(api.reloadDatabase).not.toHaveBeenCalled();
    const confirmButton = fixture.nativeElement.querySelector(
      '.file-confirmation .button-danger'
    ) as HTMLButtonElement;
    confirmButton.click();
    fixture.detectChanges();

    expect(api.reloadDatabase).toHaveBeenCalledWith(1);
    expect(fixture.nativeElement.textContent).toContain('候选配置已验证并生效');
  });
});

function setInput(root: HTMLElement, controlName: string, value: string): void {
  const input = root.querySelector(
    `input[formcontrolname="${controlName}"]`
  ) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}
