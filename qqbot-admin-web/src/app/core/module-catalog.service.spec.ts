import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ModuleCatalog, ModuleCatalogService } from './module-catalog.service';

describe('ModuleCatalogService', () => {
  let service: ModuleCatalogService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ModuleCatalogService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('orders active Web contributions and excludes stopped modules', () => {
    service.load().subscribe();
    const request = http.expectOne('/api/modules');
    request.flush({
      frameworkVersion: '1.0.0',
      modules: [
        module('operations', 'ACTIVE', [
          contribution('events', '/modules/operations/events', 40)
        ]),
        module('runtime', 'ACTIVE', [contribution('bots', '/modules/runtime/bots', 20)]),
        module('stopped', 'STOPPED', [contribution('hidden', '/hidden', 1)])
      ]
    } satisfies ModuleCatalog);

    expect(service.navigation().map((item) => item.id)).toEqual(['bots', 'events']);
    expect(service.findContribution('operations', 'events')?.route)
      .toBe('/modules/operations/events');
  });

  function module(
    id: string,
    state: 'ACTIVE' | 'STOPPED',
    webContributions: ReturnType<typeof contribution>[]
  ) {
    return {
      id,
      name: id,
      version: '1.0.0',
      state,
      error: null,
      dependencies: [],
      capabilities: [],
      webContributions,
      botSettingsContributions: [],
    };
  }

  function contribution(id: string, route: string, order: number) {
    return {
      id,
      label: id,
      route,
      icon: 'puzzle',
      order,
      componentKey: null,
      entrypoint: `/module-assets/test/${id}.js?v=sha`,
      customElement: `qqbot-test-${id}`
    };
  }
});
