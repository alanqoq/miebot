import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { BotApiService, BotConfiguration } from '../../core/bot-api.service';
import { OnboardingApiService, OnboardingStatus } from '../../core/onboarding-api.service';
import {
  DatabaseConfiguration,
  DatabaseSwitchResult,
  SystemApiService,
} from '../../core/system-api.service';
import { OnboardingPage } from './onboarding-page';

describe('OnboardingPage', () => {
  const onboardingStatus = vi.fn();
  const databaseConfigured = vi.fn();
  const complete = vi.fn();
  const getDatabaseConfiguration = vi.fn();
  const switchDatabase = vi.fn();
  const listBots = vi.fn();
  const createBot = vi.fn();

  beforeEach(async () => {
    onboardingStatus.mockReset();
    databaseConfigured.mockReset();
    complete.mockReset();
    getDatabaseConfiguration.mockReset();
    switchDatabase.mockReset();
    listBots.mockReset();
    createBot.mockReset();

    onboardingStatus.mockReturnValue(of(status('DATABASE')));
    databaseConfigured.mockReturnValue(of(status('BOT', 'SQLITE')));
    complete.mockReturnValue(of(status('COMPLETE', 'SQLITE', 1)));
    getDatabaseConfiguration.mockReturnValue(of(configuration('SQLITE', 1)));
    listBots.mockReturnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [OnboardingPage],
      providers: [
        provideRouter([]),
        {
          provide: OnboardingApiService,
          useValue: {
            status: onboardingStatus,
            databaseConfigured,
            complete,
          },
        },
        {
          provide: SystemApiService,
          useValue: { getDatabaseConfiguration, switchDatabase },
        },
        { provide: BotApiService, useValue: { list: listBots, create: createBot } },
      ],
    }).compileComponents();
  });

  it('loads the database step with SQLite selected by default', () => {
    const fixture = createFixture();

    expect(fixture.nativeElement.textContent).toContain('选择数据存储');
    expect(radio(fixture, 'SQLite').getAttribute('aria-checked')).toBe('true');
    expect(
      fixture.nativeElement.querySelector('input[formControlName="sqlitePath"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input[formControlName="host"]')).toBeNull();
  });

  it('keeps external database input available when connection validation fails', () => {
    switchDatabase.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { code: 'DATABASE_CONNECTION_FAILED' },
          }),
      ),
    );
    const fixture = createFixture();

    click(fixture, radio(fixture, 'MySQL'));
    fill(fixture, 'input[formControlName="host"]', 'mysql.internal');
    fill(fixture, 'input[formControlName="databaseName"]', 'qqbot_prod');
    fill(fixture, 'input[formControlName="username"]', 'qqbot_admin');
    fill(fixture, 'input[formControlName="password"]', 'database-password');
    submit(fixture, 'section form');

    expect(switchDatabase).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        type: 'MYSQL',
        host: 'mysql.internal',
        databaseName: 'qqbot_prod',
        username: 'qqbot_admin',
        password: 'database-password',
      }),
    );
    expect(fixture.nativeElement.textContent).toContain('无法连接数据库');
    expect(inputValue(fixture, 'input[formControlName="host"]')).toBe('mysql.internal');
    expect(inputValue(fixture, 'input[formControlName="password"]')).toBe('database-password');
    expect(fixture.nativeElement.textContent).toContain('选择数据存储');
  });

  it('enters the bot step only after the database switch and stage update succeed', () => {
    const mysql = configuration('MYSQL', 2);
    switchDatabase.mockReturnValue(of(switchResult(mysql)));
    databaseConfigured.mockReturnValue(of(status('BOT', 'MYSQL')));
    const fixture = createFixture();

    click(fixture, radio(fixture, 'MySQL'));
    fill(fixture, 'input[formControlName="password"]', 'database-password');
    submit(fixture, 'section form');

    expect(databaseConfigured).toHaveBeenCalledWith(2, 'MYSQL');
    expect(fixture.nativeElement.textContent).toContain('添加 QQ 机器人');
    expect(fixture.nativeElement.textContent).toContain('当前数据库：MySQL');
  });

  it('saves one SQLite bot and completes setup immediately', () => {
    const bot = botConfiguration('bot-1', '10001');
    onboardingStatus.mockReturnValue(of(status('BOT', 'SQLITE')));
    createBot.mockReturnValue(of(bot));
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="appId"]', '10001');
    fill(fixture, 'input[formControlName="appSecret"]', 'qq-openapi-secret');
    submit(fixture, '.bot-form');

    expect(createBot).toHaveBeenCalledWith(
      expect.objectContaining({
        appId: '10001',
        appSecret: 'qq-openapi-secret',
        environment: 'PRODUCTION',
        intents: 33_554_432,
      }),
    );
    expect(complete).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith('/dashboard');
    expect(buttonByText(fixture, '继续添加')).toBeNull();
  });

  it('allows the first bot to use the QQ sandbox environment', () => {
    onboardingStatus.mockReturnValue(of(status('BOT', 'MYSQL')));
    getDatabaseConfiguration.mockReturnValue(of(configuration('MYSQL', 2)));
    createBot.mockReturnValue(of(botConfiguration('bot-1', 'sandbox-app')));
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="appId"]', 'sandbox-app');
    select(fixture, 'select[formControlName="environment"]', 'SANDBOX');
    fill(fixture, 'input[formControlName="appSecret"]', 'qq-openapi-secret');
    submit(fixture, '.bot-form');

    expect(createBot).toHaveBeenCalledWith(
      expect.objectContaining({
        appId: 'sandbox-app',
        environment: 'SANDBOX',
        intents: 33_554_432,
      }),
    );
  });

  it('offers another bot or completion after saving a bot to MySQL', () => {
    onboardingStatus.mockReturnValue(of(status('BOT', 'MYSQL')));
    getDatabaseConfiguration.mockReturnValue(of(configuration('MYSQL', 2)));
    createBot.mockReturnValue(of(botConfiguration('bot-1', '20001')));
    const fixture = createFixture();

    fill(fixture, 'input[formControlName="appId"]', '20001');
    fill(fixture, 'input[formControlName="appSecret"]', 'qq-openapi-secret');
    submit(fixture, '.bot-form');

    expect(complete).not.toHaveBeenCalled();
    expect(buttonByText(fixture, '继续添加')).not.toBeNull();
    expect(buttonByText(fixture, '完成设置')).not.toBeNull();

    click(fixture, buttonByText(fixture, '继续添加') as HTMLButtonElement);
    expect(fixture.nativeElement.querySelector('.bot-form')).not.toBeNull();
    expect(inputValue(fixture, 'input[formControlName="appId"]')).toBe('');
  });
});

function createFixture(): ComponentFixture<OnboardingPage> {
  const fixture = TestBed.createComponent(OnboardingPage);
  fixture.detectChanges();
  fixture.detectChanges();
  return fixture;
}

function status(
  stage: OnboardingStatus['stage'],
  databaseType: OnboardingStatus['databaseType'] = null,
  botCount = 0,
): OnboardingStatus {
  return {
    stage,
    databaseType,
    botCount,
    completedAt: stage === 'COMPLETE' ? '2026-07-17T12:00:00Z' : null,
  };
}

function configuration(
  type: DatabaseConfiguration['type'],
  revision: number,
): DatabaseConfiguration {
  const external = type !== 'SQLITE';
  return {
    revision,
    type,
    sqlitePath: external ? null : '/data/qqbot.db',
    busyTimeoutMs: external ? null : 5000,
    host: external ? 'localhost' : null,
    port: type === 'MYSQL' ? 3306 : type === 'POSTGRESQL' ? 5432 : null,
    databaseName: external ? 'qqbot' : null,
    username: external ? 'qqbot' : null,
    sslMode: external ? 'PREFERRED' : null,
    connectTimeoutMs: external ? 5000 : null,
    passwordConfigured: external,
    databaseProduct: type === 'SQLITE' ? 'SQLite' : type === 'MYSQL' ? 'MySQL' : 'PostgreSQL',
    databaseVersion: 'test',
    switchInProgress: false,
    lastSwitchedAt: null,
  };
}

function switchResult(configuration: DatabaseConfiguration): DatabaseSwitchResult {
  return {
    configuration,
    verification: {
      success: true,
      type: configuration.type,
      databaseProduct: configuration.databaseProduct ?? '',
      databaseVersion: configuration.databaseVersion ?? '',
      latencyMs: 3,
      readVerified: true,
      writeVerified: true,
      schemaState: 'READY',
      schemaVersion: '4',
      initialized: false,
      adminSeeded: false,
      botCount: 0,
      testedAt: '2026-07-17T12:00:00Z',
    },
  };
}

function botConfiguration(id: string, appId: string): BotConfiguration {
  return {
    id,
    displayName: 'QQ Bot 1',
    appId,
    environment: 'PRODUCTION',
    intents: 33_554_432,
    shardIndex: 0,
    shardCount: 1,
    enabled: true,
    revision: 1,
    createdAt: '2026-07-17T12:00:00Z',
    updatedAt: '2026-07-17T12:00:00Z',
    secretConfigured: true,
  };
}

function radio(fixture: ComponentFixture<OnboardingPage>, label: string): HTMLButtonElement {
  const buttons = fixture.nativeElement.querySelectorAll(
    '[role="radio"]',
  ) as NodeListOf<HTMLButtonElement>;
  const result = Array.from(buttons).find((button) => button.textContent?.trim() === label);
  if (!result) {
    throw new Error(`Missing database radio: ${label}`);
  }
  return result;
}

function buttonByText(
  fixture: ComponentFixture<OnboardingPage>,
  label: string,
): HTMLButtonElement | null {
  const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
  return Array.from(buttons).find((button) => button.textContent?.includes(label)) ?? null;
}

function fill(fixture: ComponentFixture<OnboardingPage>, selector: string, value: string): void {
  const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  fixture.detectChanges();
}

function select(
  fixture: ComponentFixture<OnboardingPage>,
  selector: string,
  value: string,
): void {
  const element = fixture.nativeElement.querySelector(selector) as HTMLSelectElement;
  element.value = value;
  element.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

function submit(fixture: ComponentFixture<OnboardingPage>, selector: string): void {
  const form = fixture.nativeElement.querySelector(selector) as HTMLFormElement;
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  fixture.detectChanges();
}

function click(fixture: ComponentFixture<OnboardingPage>, element: HTMLElement): void {
  element.click();
  fixture.detectChanges();
}

function inputValue(fixture: ComponentFixture<OnboardingPage>, selector: string): string {
  return (fixture.nativeElement.querySelector(selector) as HTMLInputElement).value;
}
