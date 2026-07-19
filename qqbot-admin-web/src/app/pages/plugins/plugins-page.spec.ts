import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { BotApiService } from '../../core/bot-api.service';
import { EventApiService } from '../../core/event-api.service';
import { PluginApiService, PluginInventory } from '../../core/plugin-api.service';
import { PluginsPage } from './plugins-page';

describe('PluginsPage', () => {
  const list = vi.fn();
  const listBindings = vi.fn();
  const reload = vi.fn();

  beforeEach(async () => {
    list.mockReset().mockReturnValue(of(inventoryFixture()));
    listBindings.mockReset().mockReturnValue(of([]));
    reload.mockReset().mockReturnValue(of(inventoryFixture()));
    await TestBed.configureTestingModule({
      imports: [PluginsPage],
      providers: [
        {
          provide: PluginApiService,
          useValue: {
            list,
            listBindings,
            reload,
            createBinding: vi.fn(),
            updateBinding: vi.fn(),
            deleteBinding: vi.fn(),
          },
        },
        { provide: BotApiService, useValue: { list: vi.fn().mockReturnValue(of([])) } },
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

  it('renders scanned artifacts and truthful unloaded runtime state', () => {
    const fixture = createFixture();

    expect(list).toHaveBeenCalledWith('');
    expect(fixture.nativeElement.textContent).toContain('Support Plugin');
    expect(fixture.nativeElement.textContent).toContain('宿主未加载');
    expect(fixture.nativeElement.textContent).toContain('发现制品');
  });

  it('passes the search term when scanning again', () => {
    const fixture = createFixture();
    const input = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = 'support';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.plugins-toolbar button').click();
    expect(list).toHaveBeenLastCalledWith('support');
  });

  it('keeps a loaded result visible when a refresh fails', () => {
    const fixture = createFixture();
    list.mockReturnValueOnce(throwError(() => new Error('offline')));
    fixture.nativeElement.querySelectorAll('.page-header button')[1].click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Support Plugin');
    expect(fixture.nativeElement.textContent).toContain('无法读取插件目录');
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
      },
    ],
    directory: '/plugins',
    directoryExists: true,
    runtimeAvailable: false,
    scanError: null,
    scannedAt: '2026-07-18T12:00:00Z',
  };
}
