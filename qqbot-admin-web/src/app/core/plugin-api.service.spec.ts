import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PluginApiService } from './plugin-api.service';

describe('PluginApiService', () => {
  let api: PluginApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(PluginApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('scans the plugin inventory with a normalized query', () => {
    api.list('  support plugin  ').subscribe();

    const request = http.expectOne('/api/plugins?query=support%20plugin');
    expect(request.request.method).toBe('GET');
    request.flush({
      items: [],
      directory: '/plugins',
      directoryExists: true,
      runtimeAvailable: false,
      scanError: null,
      scannedAt: '2026-07-18T12:00:00Z',
    });
  });

  it('omits an empty search parameter', () => {
    api.list('   ').subscribe();

    const request = http.expectOne('/api/plugins');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({});
  });

  it('uses the plugin binding resource for durable binding changes', () => {
    api.createBinding({ pluginId: 'echo', botId: 'bot-1', configJson: '{}', enabled: true }).subscribe();
    const create = http.expectOne('/api/plugin-bindings');
    expect(create.request.method).toBe('POST');
    create.flush({ id: 'binding-1' });

    api.updateBinding('binding-1', { expectedRevision: 0, configJson: '{}', enabled: false }).subscribe();
    const update = http.expectOne('/api/plugin-bindings/binding-1');
    expect(update.request.method).toBe('PUT');
    update.flush({ id: 'binding-1' });

    api.deleteBinding('binding-1').subscribe();
    const remove = http.expectOne('/api/plugin-bindings/binding-1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });
});
