import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  ViewChild,
  signal,
} from '@angular/core';

interface BotSettingsContribution {
  moduleId: string;
  id: string;
  label: string;
  order: number;
  entrypoint: string;
  customElement: string;
}

interface ModuleCatalogResponse {
  modules: Array<{
    id: string;
    state: string;
    botSettingsContributions?: Array<Omit<BotSettingsContribution, 'moduleId'>>;
  }>;
}

@Component({
  selector: 'qqbot-bot-settings-host',
  template: `
    <div #host></div>
    @if (error()) {
      <div class="settings-error" role="alert">{{ error() }}</div>
    }
  `,
  styles: `
    :host { display: block; border-top: 1px solid var(--border); }
    .settings-error { padding: 12px 20px; color: #7c2f2f; background: #fff0f0; }
  `,
})
export class BotSettingsHostComponent implements AfterViewInit, OnChanges {
  @Input({ required: true }) botId = '';
  @ViewChild('host', { static: true }) private host!: ElementRef<HTMLDivElement>;

  protected readonly error = signal<string | null>(null);
  private ready = false;
  private generation = 0;

  ngAfterViewInit(): void {
    this.ready = true;
    void this.render();
  }

  ngOnChanges(): void {
    if (this.ready) {
      void this.render();
    }
  }

  private async render(): Promise<void> {
    const generation = ++this.generation;
    this.error.set(null);
    this.host.nativeElement.replaceChildren();
    if (!this.botId || !window.qqbot) {
      return;
    }
    try {
      const response = await window.qqbot.request('/api/modules');
      if (!response.ok) {
        throw new Error('Unable to load module settings');
      }
      const catalog = (await response.json()) as ModuleCatalogResponse;
      const contributions = catalog.modules
        .filter((module) => module.state === 'ACTIVE')
        .flatMap((module) =>
          (module.botSettingsContributions ?? []).map((contribution) => ({
            ...contribution,
            moduleId: module.id,
          })),
        )
        .sort((left, right) => left.order - right.order || left.label.localeCompare(right.label));
      for (const contribution of contributions) {
        await loadScript(contribution);
        if (generation !== this.generation) {
          return;
        }
        const element = document.createElement(contribution.customElement);
        element.setAttribute('bot-id', this.botId);
        this.host.nativeElement.appendChild(element);
      }
    } catch {
      if (generation === this.generation) {
        this.error.set('模块设置加载失败。');
      }
    }
  }
}

const scriptLoads = new Map<string, Promise<void>>();

function loadScript(contribution: BotSettingsContribution): Promise<void> {
  if (customElements.get(contribution.customElement)) {
    return Promise.resolve();
  }
  const existing = scriptLoads.get(contribution.entrypoint);
  if (existing) {
    return existing;
  }
  const loading = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.type = 'module';
    script.src = contribution.entrypoint;
    script.addEventListener('load', () => {
      customElements.whenDefined(contribution.customElement).then(() => resolve(), reject);
    });
    script.addEventListener('error', () => reject(new Error('Module settings asset failed')));
    document.head.appendChild(script);
  });
  scriptLoads.set(contribution.entrypoint, loading);
  loading.catch(() => scriptLoads.delete(contribution.entrypoint));
  return loading;
}
