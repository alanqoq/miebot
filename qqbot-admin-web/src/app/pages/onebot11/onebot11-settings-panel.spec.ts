import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OneBot11SettingsPanel } from './onebot11-settings-panel';

describe('OneBot11SettingsPanel', () => {
  const botId = '550e8400-e29b-41d4-a716-446655440000';
  let fixture: ComponentFixture<OneBot11SettingsPanel>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OneBot11SettingsPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OneBot11SettingsPanel);
    fixture.nativeElement.setAttribute('bot-id', botId);
    fixture.detectChanges();
    http.expectOne(`/api/bots/${botId}/onebot11`).flush(response());
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('saves forward WebSocket settings with a write-only token', () => {
    setChecked('enabled', true);
    setChecked('forwardEnabled', true);
    fill('accessToken', 'onebot-token');
    button('保存 OneBot 配置').click();

    const request = http.expectOne(`/api/bots/${botId}/onebot11`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toMatchObject({
      expectedRevision: 0,
      enabled: true,
      forwardEnabled: true,
      forwardBindAddress: '127.0.0.1',
      forwardPort: 5700,
      accessToken: 'onebot-token',
    });
    request.flush(response({ revision: 1, enabled: true, forwardEnabled: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('OneBot 配置已保存');
    expect(input('accessToken').value).toBe('');
  });

  function setChecked(name: string, checked: boolean): void {
    const value = input(name);
    value.checked = checked;
    value.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
  }

  function fill(name: string, value: string): void {
    const field = input(name);
    field.value = value;
    field.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  function input(name: string): HTMLInputElement {
    const value = fixture.nativeElement.querySelector(
      `input[formControlName="${name}"]`,
    ) as HTMLInputElement | null;
    if (!value) throw new Error(`Missing input ${name}`);
    return value;
  }

  function button(label: string): HTMLButtonElement {
    const values = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    const value = Array.from(values).find((candidate) => candidate.textContent?.includes(label));
    if (!value) throw new Error(`Missing button ${label}`);
    return value;
  }

  function response(settings: Partial<Record<string, unknown>> = {}) {
    return {
      settings: {
        botId,
        enabled: false,
        forwardEnabled: false,
        forwardBindAddress: '127.0.0.1',
        forwardPort: null,
        reverseEnabled: false,
        reverseUrl: null,
        accessTokenConfigured: false,
        heartbeatEnabled: true,
        heartbeatIntervalMs: 15000,
        reconnectIntervalMs: 3000,
        revision: 0,
        ...settings,
      },
      runtime: {
        state: 'DISABLED',
        forwardListening: false,
        forwardConnections: 0,
        reverseConnected: false,
        lastError: null,
      },
    };
  }
});
