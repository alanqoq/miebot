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

    api.updateBinding('binding-1', { expectedRevision: 0, enabled: false }).subscribe();
    const update = http.expectOne('/api/plugin-bindings/binding-1');
    expect(update.request.method).toBe('PUT');
    update.flush({ id: 'binding-1' });

    api.deleteBinding('binding-1').subscribe();
    const remove = http.expectOne('/api/plugin-bindings/binding-1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });

  it('manages files inside a plugin binding directory', () => {
    api.listBindingFiles('binding 1').subscribe();
    const root = http.expectOne('/api/plugin-bindings/binding%201/files?path=');
    expect(root.request.method).toBe('GET');
    root.flush({ path: '', entries: [] });

    api.listBindingFiles('binding 1', 'images/cache').subscribe();
    const list = http.expectOne('/api/plugin-bindings/binding%201/files?path=images/cache');
    expect(list.request.method).toBe('GET');
    list.flush({ path: 'images/cache', entries: [] });

    api.getBindingFileContent('binding 1', 'config.json').subscribe();
    const content = http.expectOne('/api/plugin-bindings/binding%201/files/content?path=config.json');
    expect(content.request.method).toBe('GET');
    content.flush({ path: 'config.json', content: '{}', sha256: 'abc', modifiedAt: '' });

    api.saveBindingFileContent('binding 1', {
      path: 'config.json', content: '{"enabled":true}', expectedSha256: 'abc',
    }).subscribe();
    const save = http.expectOne('/api/plugin-bindings/binding%201/files/content');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({
      path: 'config.json', content: '{"enabled":true}', expectedSha256: 'abc',
    });
    save.flush({});

    api.createBindingFileEntry('binding 1', { path: 'images', directory: true }).subscribe();
    const create = http.expectOne('/api/plugin-bindings/binding%201/files/entries');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({ path: 'images', directory: true });
    create.flush({
      name: 'images', path: 'images', directory: true, sizeBytes: 0,
      modifiedAt: '2026-07-18T12:00:00Z', contentType: null,
    });

    const file = new File([new Uint8Array([1, 2, 3])], 'avatar.png', { type: 'image/png' });
    api.uploadBindingFile('binding 1', 'images/cache', file).subscribe();
    const upload = http.expectOne(
      '/api/plugin-bindings/binding%201/files/upload?directory=images/cache&overwrite=false',
    );
    expect(upload.request.method).toBe('POST');
    expect(upload.request.body).toBeInstanceOf(FormData);
    const uploaded = (upload.request.body as FormData).get('file') as File;
    expect(uploaded.name).toBe(file.name);
    expect(uploaded.size).toBe(file.size);
    expect(uploaded.type).toBe(file.type);
    upload.flush({
      name: file.name, path: `images/cache/${file.name}`, directory: false,
      sizeBytes: file.size, modifiedAt: '2026-07-18T12:00:00Z', contentType: file.type,
    });

    api.uploadBindingFile('binding 1', 'images/cache', file, true, 'old-sha').subscribe();
    const overwrite = http.expectOne(
      '/api/plugin-bindings/binding%201/files/upload?directory=images/cache&overwrite=true&expectedSha256=old-sha',
    );
    expect(overwrite.request.method).toBe('POST');
    overwrite.flush({
      name: file.name, path: `images/cache/${file.name}`, directory: false,
      sizeBytes: file.size, modifiedAt: '2026-07-18T12:00:00Z', contentType: file.type,
    });

    api.downloadBindingFile('binding 1', 'images/report 1.pdf').subscribe();
    const download = http.expectOne(
      '/api/plugin-bindings/binding%201/files/download?path=images/report%201.pdf',
    );
    expect(download.request.method).toBe('GET');
    expect(download.request.responseType).toBe('blob');
    download.flush(new Blob(['report'], { type: 'application/pdf' }));

    api.deleteBindingFile('binding 1', 'images/old.png').subscribe();
    const remove = http.expectOne('/api/plugin-bindings/binding%201/files?path=images/old.png');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });

  it('uploads a trusted plugin jar as multipart data with progress events', () => {
    const file = new File([new Uint8Array([1, 2, 3])], 'support.jar', {
      type: 'application/java-archive',
    });
    let operation: string | undefined;
    api.upload(file).subscribe((event) => {
      if ('body' in event && event.body) operation = event.body.operation;
    });

    const request = http.expectOne('/api/plugins/upload');
    expect(request.request.method).toBe('POST');
    expect(request.request.reportProgress).toBe(true);
    expect(request.request.headers.get('X-Plugin-Upload-Confirm')).toBe('trusted-jar');
    expect(request.request.body).toBeInstanceOf(FormData);
    const uploaded = (request.request.body as FormData).get('file') as File;
    expect(uploaded.name).toBe(file.name);
    expect(uploaded.size).toBe(file.size);
    request.flush({ operation: 'INSTALLED', artifact: {}, previousVersion: null, previousSha256: null });

    expect(operation).toBe('INSTALLED');
  });
});
