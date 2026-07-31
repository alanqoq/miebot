import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpResponse } from '@angular/common/http';
import { NEVER, Subject, of, throwError } from 'rxjs';
import { BotApiService, BotConfiguration } from '../../core/bot-api.service';
import { EventApiService } from '../../core/event-api.service';
import {
  PluginApiService,
  PluginBinding,
  PluginInventory,
  PluginUploadResponse,
} from '../../core/plugin-api.service';
import { PluginsPage } from './plugins-page';

describe('PluginsPage', () => {
  const list = vi.fn();
  const listBindings = vi.fn();
  const reload = vi.fn();
  const upload = vi.fn();
  const botList = vi.fn();
  const getRuntimeSummary = vi.fn();
  const observeRuntime = vi.fn();
  const listBindingFiles = vi.fn();
  const createBinding = vi.fn();
  const deleteBinding = vi.fn();
  const resetBinding = vi.fn();
  const createBindingFileEntry = vi.fn();
  const uploadBindingFile = vi.fn();
  const downloadBindingFile = vi.fn();
  const deleteBindingFile = vi.fn();
  const getBindingFileContent = vi.fn();
  const saveBindingFileContent = vi.fn();

  beforeEach(async () => {
    list.mockReset().mockReturnValue(of(inventoryFixture()));
    listBindings.mockReset().mockReturnValue(of([]));
    reload.mockReset().mockReturnValue(of(inventoryFixture()));
    upload.mockReset().mockReturnValue(of(new HttpResponse<PluginUploadResponse>({
      body: uploadResponseFixture(),
    })));
    botList.mockReset().mockReturnValue(of([]));
    getRuntimeSummary.mockReset().mockReturnValue(of({ bots: [] }));
    observeRuntime.mockReset().mockReturnValue(NEVER);
    listBindingFiles.mockReset().mockReturnValue(of({ path: '', entries: [] }));
    createBinding.mockReset().mockReturnValue(of(bindingFixture()));
    deleteBinding.mockReset().mockReturnValue(of(undefined));
    resetBinding.mockReset().mockReturnValue(of(bindingFixture()));
    createBindingFileEntry.mockReset().mockReturnValue(of({}));
    uploadBindingFile.mockReset().mockReturnValue(of({}));
    downloadBindingFile.mockReset().mockReturnValue(of(new Blob()));
    deleteBindingFile.mockReset().mockReturnValue(of(undefined));
    getBindingFileContent.mockReset().mockReturnValue(of({
      path: 'config.json', content: '{}', sha256: 'abc', modifiedAt: '2026-07-18T12:00:00Z',
    }));
    saveBindingFileContent.mockReset().mockReturnValue(of({
      path: 'config.json', content: '{}', sha256: 'def', modifiedAt: '2026-07-18T12:00:00Z',
    }));
    await TestBed.configureTestingModule({
      imports: [PluginsPage],
      providers: [
        {
          provide: PluginApiService,
          useValue: {
            list,
            listBindings,
            reload,
            upload,
            createBinding,
            deleteBinding,
            resetBinding,
            listBindingFiles,
            createBindingFileEntry,
            uploadBindingFile,
            downloadBindingFile,
            deleteBindingFile,
            getBindingFileContent,
            saveBindingFileContent,
          },
        },
        {
          provide: BotApiService,
          useValue: {
            list: botList,
            getRuntimeSummary,
            observeRuntime,
          },
        },
        {
          provide: EventApiService,
          useValue: {
            listPluginDeliveries: vi.fn().mockReturnValue(of({
              items: [], nextCursor: null, hasMore: false,
              observedAt: '2026-07-18T12:00:00Z',
              stats: {
                totalCount: 0, pendingCount: 0, inProgressCount: 0,
                retryWaitCount: 0, succeededCount: 0, deadLetterCount: 0, pausedCount: 0,
              },
            })),
          },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  it('renders scanned artifacts and truthful unloaded runtime state', () => {
    const fixture = createFixture();

    expect(list).toHaveBeenCalledWith();
    expect(fixture.nativeElement.textContent).toContain('Support Plugin');
    expect(fixture.nativeElement.textContent).toContain('宿主未加载');
    expect(fixture.nativeElement.textContent).toContain('发现制品');
  });

  it('filters the visible inventory while rescanning the complete catalog', () => {
    const fixture = createFixture();
    const input = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = 'missing';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.plugin-name')).toBeNull();
    fixture.nativeElement.querySelector('.plugins-toolbar button').click();
    expect(list).toHaveBeenLastCalledWith();
  });

  it('keeps a loaded result visible when a refresh fails', () => {
    const fixture = createFixture();
    list.mockReturnValueOnce(throwError(() => new Error('offline')));
    fixture.nativeElement.querySelectorAll('.page-header button')[2].click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Support Plugin');
    expect(fixture.nativeElement.textContent).toContain('无法读取插件目录');
  });

  it('selects, confirms and uploads a jar from the plugin page', () => {
    const fixture = createFixture();
    const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File([new Uint8Array([1, 2, 3])], 'support.jar', {
      type: 'application/java-archive',
    });
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });

    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('我确认该 JAR 来自可信来源');

    const confirmation = fixture.nativeElement.querySelector('.upload-confirmation input') as HTMLInputElement;
    confirmation.click();
    fixture.detectChanges();
    const confirmButton = [...fixture.nativeElement.querySelectorAll('.upload-dialog .button')]
      .find((button: Element) => button.textContent?.includes('确认上传')) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(false);
    confirmButton.click();
    fixture.detectChanges();

    expect(upload).toHaveBeenCalledWith(file);
    expect(fixture.nativeElement.textContent).toContain('已热升级');
    expect(fixture.nativeElement.textContent).toContain('2.0.0');
  });

  it('groups bindings by bot and opens its plugin file manager', () => {
    listBindings.mockReturnValue(of([bindingFixture()]));
    botList.mockReturnValue(of([botFixture()]));
    getRuntimeSummary.mockReturnValue(of({
      bots: [{ botId: 'bot-1', state: 'ONLINE' }],
    }));

    const fixture = createFixture();
    expect(fixture.nativeElement.textContent).toContain('运营机器人');
    expect(fixture.nativeElement.textContent).toContain('在线');
    expect(fixture.nativeElement.textContent).toContain('1 个');

    const edit = fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement;
    edit.click();
    fixture.detectChanges();

    expect(listBindingFiles).toHaveBeenCalledWith('binding-1', '');
    expect(fixture.nativeElement.textContent).toContain('根目录');
    expect(fixture.nativeElement.textContent).toContain('此文件夹为空');
    const bindingHeader = fixture.nativeElement.querySelector('.binding-section-header') as HTMLElement;
    expect(bindingHeader.querySelector('h3')?.textContent?.trim()).toBe('Support Plugin');
    expect(bindingHeader.querySelector('[role="switch"]')).toBeNull();
    expect(bindingHeader.querySelector('.status-badge')).toBeNull();
    expect(bindingHeader.querySelectorAll('button')).toHaveLength(1);
    expect(bindingHeader.querySelector('button')?.getAttribute('aria-label')).toBe('删除插件绑定');
  });

  it('keeps the complete plugin catalog available to bot bindings after searching', () => {
    const inventory = loadedInventoryFixture();
    list.mockReturnValue(of({
      ...inventory,
      items: [
        ...inventory.items,
        { ...inventory.items[0], id: 'other', name: 'Other Plugin', fileName: 'other.jar' },
      ],
    }));
    listBindings.mockReturnValue(of([bindingFixture()]));
    botList.mockReturnValue(of([botFixture()]));

    const fixture = createFixture();
    const search = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    search.value = 'other';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.binding-section-header h3')?.textContent?.trim())
      .toBe('Support Plugin');

    const bindButton = fixture.nativeElement.querySelector('.detail-header .button-primary') as HTMLButtonElement;
    expect(bindButton.disabled).toBe(false);
    bindButton.click();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.plugin-dialog select') as HTMLSelectElement).value)
      .toBe('other');
  });

  it('applies live Gateway updates from the runtime stream', () => {
    const updates = new Subject<{
      totalCount: number;
      enabledCount: number;
      connectedCount: number;
      observedAt: string;
      bots: Array<Record<string, unknown>>;
    }>();
    observeRuntime.mockReturnValue(updates);
    botList.mockReturnValue(of([botFixture()]));

    const fixture = createFixture();
    expect(fixture.nativeElement.textContent).toContain('状态未知');
    updates.next({
      totalCount: 1,
      enabledCount: 1,
      connectedCount: 1,
      observedAt: '2026-07-18T12:01:00Z',
      bots: [{ botId: 'bot-1', state: 'ONLINE' }],
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('在线');
  });

  it('periodically refreshes bots and bindings changed outside this page', () => {
    vi.useFakeTimers();
    try {
      const refreshedBot = { ...botFixture(), displayName: '更新后的机器人' };
      botList.mockReset()
        .mockReturnValueOnce(of([botFixture()]))
        .mockReturnValueOnce(of([refreshedBot]));
      listBindings.mockReset()
        .mockReturnValueOnce(of([]))
        .mockReturnValueOnce(of([bindingFixture()]));

      const fixture = createFixture();
      expect(fixture.nativeElement.textContent).toContain('运营机器人');
      expect(fixture.nativeElement.textContent).toContain('0 个');

      vi.advanceTimersByTime(15_000);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('更新后的机器人');
      expect(fixture.nativeElement.textContent).toContain('1 个');
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('binds an available plugin directly from the selected bot detail', () => {
    list.mockReturnValue(of(loadedInventoryFixture()));
    botList.mockReturnValue(of([botFixture()]));
    createBinding.mockReturnValue(of(bindingFixture()));

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();

    const bindButton = fixture.nativeElement.querySelector('.detail-header .button-primary') as HTMLButtonElement;
    expect(bindButton.textContent).toContain('绑定插件');
    expect(bindButton.disabled).toBe(false);
    bindButton.click();
    fixture.detectChanges();

    const selects = fixture.nativeElement.querySelectorAll('.plugin-dialog select') as NodeListOf<HTMLSelectElement>;
    expect(selects[0].value).toBe('support');
    expect(selects[1].value).toBe('bot-1');
    expect(selects[1].disabled).toBe(true);
    expect((fixture.nativeElement.querySelector('.plugin-dialog textarea') as HTMLTextAreaElement).value)
      .toBe('{\n  "enabled": true\n}');

    (fixture.nativeElement.querySelector('.plugin-dialog button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(createBinding).toHaveBeenCalledWith({
      pluginId: 'support',
      botId: 'bot-1',
      configContent: '{\n  "enabled": true\n}',
      enabled: true,
    });
    expect(listBindingFiles).toHaveBeenCalledWith('binding-1', '');
    expect(fixture.nativeElement.querySelector('.plugin-dialog')).toBeNull();
  });

  it('names the binding dialog and restores focus to its trigger', async () => {
    list.mockReturnValue(of(loadedInventoryFixture()));
    botList.mockReturnValue(of([botFixture()]));

    const fixture = createFixture();
    const trigger = fixture.nativeElement.querySelector(
      '.plugin-table .icon-button, .table-panel .table-actions .icon-button',
    ) as HTMLButtonElement;
    const bindTrigger = trigger ?? fixture.nativeElement.querySelector(
      'table tbody .table-actions .icon-button',
    ) as HTMLButtonElement;
    bindTrigger.focus();
    bindTrigger.click();
    fixture.detectChanges();
    await Promise.resolve();

    const dialog = fixture.nativeElement.querySelector('.plugin-dialog') as HTMLElement;
    expect(dialog.getAttribute('aria-labelledby')).toBe('plugin-binding-title');
    expect(document.activeElement).toBe(dialog.querySelector('select'));

    (dialog.querySelector('[aria-label="关闭"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await Promise.resolve();
    expect(document.activeElement).toBe(bindTrigger);
  });

  it('accepts default configuration larger than 64 KiB and submits it unchanged', () => {
    list.mockReturnValue(of(loadedInventoryFixture()));
    botList.mockReturnValue(of([botFixture()]));

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('table tbody .table-actions .icon-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const textarea = fixture.nativeElement.querySelector('.plugin-dialog textarea') as HTMLTextAreaElement;
    const largeContent = `{\"message\":\"${'x'.repeat(70_000)}\"}`;
    textarea.value = largeContent;
    textarea.dispatchEvent(new Event('input'));
    textarea.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.plugin-dialog button[type="submit"]') as HTMLButtonElement).click();
    expect(createBinding).toHaveBeenCalledWith({
      pluginId: 'support',
      botId: 'bot-1',
      configContent: largeContent,
      enabled: true,
    });
  });

  it('warns that deleting a binding permanently removes all binding files', () => {
    list.mockReturnValue(of(loadedInventoryFixture()));
    listBindings.mockReturnValue(of([bindingFixture()]));
    botList.mockReturnValue(of([botFixture()]));
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[aria-label="删除插件绑定"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(confirm).toHaveBeenCalledWith(
      '确认删除这个机器人插件绑定？此操作会永久删除该绑定文件夹内的全部文件，且无法恢复。',
    );
    expect(deleteBinding).toHaveBeenCalledWith('binding-1');
    expect(fixture.nativeElement.querySelector('.plugin-binding-section')).toBeNull();
  });

  it('refreshes the touched binding revision after every file write', () => {
    list.mockReturnValue(of(loadedInventoryFixture()));
    botList.mockReturnValue(of([botFixture()]));
    listBindings.mockReset()
      .mockReturnValueOnce(of([bindingFixture(2)]))
      .mockReturnValueOnce(of([bindingFixture(3)]))
      .mockReturnValueOnce(of([bindingFixture(4)]))
      .mockReturnValueOnce(of([bindingFixture(5)]))
      .mockReturnValueOnce(of([bindingFixture(6)]));
    listBindingFiles.mockImplementation((_bindingId: string, path: string) => of({
      path,
      entries: path === ''
        ? [{
            name: 'cache', path: 'cache', directory: true, sizeBytes: 0,
            modifiedAt: '2026-07-18T12:00:00Z', contentType: null,
          }]
        : [
            {
              name: 'config.json', path: 'cache/config.json', directory: false, sizeBytes: 2,
              modifiedAt: '2026-07-18T12:00:00Z', contentType: 'application/json',
            },
            {
              name: 'old.txt', path: 'cache/old.txt', directory: false, sizeBytes: 3,
              modifiedAt: '2026-07-18T12:00:00Z', contentType: 'text/plain',
            },
          ],
    }));
    getBindingFileContent.mockReturnValue(of({
      path: 'cache/config.json', content: '{}', sha256: 'abc',
      modifiedAt: '2026-07-18T12:00:00Z',
    }));
    saveBindingFileContent.mockReturnValue(of({
      path: 'cache/config.json', content: '{}', sha256: 'def',
      modifiedAt: '2026-07-18T12:00:00Z',
    }));
    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('notes.db');
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const directoryButton = fixture.nativeElement.querySelector('.file-entry-button') as HTMLButtonElement;
    expect(directoryButton.getAttribute('aria-label')).toBe('打开文件夹 cache');
    directoryButton.click();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[aria-label="新建文件"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(prompt).toHaveBeenCalledWith('文件名称');
    expect(createBindingFileEntry).toHaveBeenCalledWith(
      'binding-1',
      { path: 'cache/notes.db', directory: false },
    );

    const uploadedFile = new File(['image'], 'avatar.png', { type: 'image/png' });
    const fileInput = fixture.nativeElement.querySelector(
      '.plugin-binding-section .plugin-file-input',
    ) as HTMLInputElement;
    Object.defineProperty(fileInput, 'files', { configurable: true, value: [uploadedFile] });
    fileInput.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(uploadBindingFile).toHaveBeenCalledWith('binding-1', 'cache', uploadedFile, false);

    const configRow = [...fixture.nativeElement.querySelectorAll('.file-row')]
      .find((row: Element) => row.textContent?.includes('config.json')) as HTMLTableRowElement;
    (configRow.querySelector('.file-entry-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.config-editor .button-primary') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(saveBindingFileContent).toHaveBeenCalledWith('binding-1', {
      path: 'cache/config.json', content: '{}', expectedSha256: 'abc',
    });

    const oldFileRow = [...fixture.nativeElement.querySelectorAll('.file-row')]
      .find((row: Element) => row.textContent?.includes('old.txt')) as HTMLTableRowElement;
    (oldFileRow.querySelector('[aria-label="删除文件"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(confirm).toHaveBeenCalledWith('确认删除文件“old.txt”？');
    expect(deleteBindingFile).toHaveBeenCalledWith('binding-1', 'cache/old.txt');

    expect(listBindings).toHaveBeenCalledTimes(5);
    expect(fixture.nativeElement.querySelector('.binding-detail')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.breadcrumbs')?.textContent).toContain('cache');
    expect(listBindingFiles).toHaveBeenLastCalledWith('binding-1', 'cache');
  });

  it('preserves a yaml default when creating a binding', () => {
    const yaml = '# plugin setting\nenabled: true\nmessage: hello\n';
    const inventory = loadedInventoryFixture();
    inventory.items = inventory.items.map((item) => ({
      ...item,
      defaultConfigJson: null,
      defaultConfigContent: yaml,
      configFormat: 'YAML',
      configFileName: 'config.yml',
    }));
    list.mockReturnValue(of(inventory));
    botList.mockReturnValue(of([botFixture()]));

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.detail-header .button-primary') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.plugin-dialog').textContent).toContain('config.yml');
    expect((fixture.nativeElement.querySelector('.plugin-dialog textarea') as HTMLTextAreaElement).value).toBe(yaml);
    (fixture.nativeElement.querySelector('.plugin-dialog button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(createBinding).toHaveBeenCalledWith({
      pluginId: 'support',
      botId: 'bot-1',
      configContent: yaml,
      enabled: true,
    });
  });

  it('opens and saves yaml files without json formatting', () => {
    const yaml = '# keep this comment\nenabled: true\n';
    list.mockReturnValue(of(loadedInventoryFixture()));
    botList.mockReturnValue(of([botFixture()]));
    listBindings.mockReturnValue(of([bindingFixture()]));
    listBindingFiles.mockReturnValue(of({
      path: '',
      entries: [{
        name: 'config.yml', path: 'config.yml', directory: false, sizeBytes: yaml.length,
        modifiedAt: '2026-07-18T12:00:00Z', contentType: 'application/yaml',
      }],
    }));
    getBindingFileContent.mockReturnValue(of({
      path: 'config.yml', content: yaml, sha256: 'abc', modifiedAt: '2026-07-18T12:00:00Z',
    }));
    saveBindingFileContent.mockReturnValue(of({
      path: 'config.yml', content: yaml, sha256: 'def', modifiedAt: '2026-07-18T12:00:00Z',
    }));

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const entry = fixture.nativeElement.querySelector('.file-entry-button') as HTMLButtonElement;
    expect(entry.getAttribute('aria-label')).toBe('编辑配置文件 config.yml');
    entry.click();
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('.config-editor textarea') as HTMLTextAreaElement).value).toBe(yaml);
    (fixture.nativeElement.querySelector('.config-editor .button-primary') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(saveBindingFileContent).toHaveBeenCalledWith('binding-1', {
      path: 'config.yml', content: yaml, expectedSha256: 'abc',
    });
  });

  it('requires confirmation before overwriting an existing binding file', () => {
    list.mockReturnValue(of(loadedInventoryFixture()));
    listBindings.mockReturnValue(of([bindingFixture()]));
    botList.mockReturnValue(of([botFixture()]));
    listBindingFiles.mockReturnValue(of({
      path: '',
      entries: [{
        name: 'avatar.png', path: 'avatar.png', directory: false, sizeBytes: 3,
        modifiedAt: '2026-07-18T12:00:00Z', contentType: 'image/png',
      }],
    }));
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);

    const fixture = createFixture();
    (fixture.nativeElement.querySelector('.bot-binding-table .compact-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const uploadedFile = new File(['replacement'], 'avatar.png', { type: 'image/png' });
    const input = fixture.nativeElement.querySelector(
      '.plugin-binding-section .plugin-file-input',
    ) as HTMLInputElement;
    Object.defineProperty(input, 'files', { configurable: true, value: [uploadedFile] });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(confirm).toHaveBeenCalledWith('文件“avatar.png”已存在，确认覆盖？原文件将无法恢复。');
    expect(uploadBindingFile).toHaveBeenCalledWith('binding-1', '', uploadedFile, true);
  });
});

function createFixture(): ComponentFixture<PluginsPage> {
  const fixture = TestBed.createComponent(PluginsPage);
  fixture.detectChanges();
  return fixture;
}

function inventoryFixture(): PluginInventory {
  return {
    items: [
      {
        id: 'support',
        name: 'Support Plugin',
        version: '1.0.0',
        apiCompatibility: '1',
        fileName: 'support.jar',
        sizeBytes: 1024,
        modifiedAt: '2026-07-18T12:00:00Z',
        sha256: '1234567890abcdef1234567890abcdef',
        status: 'DISCOVERED',
        error: null,
        loaded: false,
        bindingCount: 0,
        enabledBindingCount: 0,
        defaultConfigJson: '{"enabled":true}',
      },
    ],
    directory: '/plugins',
    directoryExists: true,
    runtimeAvailable: false,
    scanError: null,
    scannedAt: '2026-07-18T12:00:00Z',
  };
}

function loadedInventoryFixture(): PluginInventory {
  const inventory = inventoryFixture();
  return {
    ...inventory,
    runtimeAvailable: true,
    items: inventory.items.map((item) => ({ ...item, status: 'LOADED', loaded: true })),
  };
}

function botFixture(): BotConfiguration {
  return {
    id: 'bot-1', displayName: '运营机器人', appId: 'app-1', environment: 'PRODUCTION',
    intents: 0, shardIndex: 0, shardCount: 1, enabled: true, revision: 1,
    createdAt: '2026-07-18T10:00:00Z', updatedAt: '2026-07-18T11:00:00Z',
    secretConfigured: true,
  };
}

function bindingFixture(revision = 2): PluginBinding {
  return {
    id: 'binding-1', pluginId: 'support', botId: 'bot-1', enabled: true, revision,
    createdAt: '2026-07-18T10:00:00Z', updatedAt: '2026-07-18T12:00:00Z',
    runtimeState: 'ACTIVE', runtimeError: null,
  };
}

function uploadResponseFixture(): PluginUploadResponse {
  return {
    operation: 'UPGRADED',
    artifact: {
      ...inventoryFixture().items[0],
      version: '2.0.0',
      status: 'LOADED',
      loaded: true,
      sha256: 'abcdefabcdefabcdefabcdefabcdef12',
    },
    previousVersion: '1.0.0',
    previousSha256: '1234567890abcdef1234567890abcdef',
  };
}
