import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, finalize, shareReplay, tap } from 'rxjs';

export interface ModuleDependency {
  moduleId: string;
  minimumVersion: string;
  optional: boolean;
}

export interface ModuleWebContribution {
  moduleId: string;
  id: string;
  label: string;
  route: string;
  icon: string;
  order: number;
  componentKey: string | null;
  entrypoint: string | null;
  customElement: string | null;
}

export interface ModuleBotSettingsContribution {
  moduleId: string;
  id: string;
  label: string;
  order: number;
  entrypoint: string;
  customElement: string;
}

export interface FrameworkModuleInfo {
  id: string;
  name: string;
  version: string;
  state: 'DISCOVERED' | 'STARTING' | 'ACTIVE' | 'STOPPING' | 'STOPPED' | 'FAILED';
  error: string | null;
  dependencies: ModuleDependency[];
  capabilities: string[];
  webContributions: Omit<ModuleWebContribution, 'moduleId'>[];
  botSettingsContributions: Omit<ModuleBotSettingsContribution, 'moduleId'>[];
}

export interface ModuleCatalog {
  frameworkVersion: string;
  modules: FrameworkModuleInfo[];
}

@Injectable({ providedIn: 'root' })
export class ModuleCatalogService {
  private readonly http = inject(HttpClient);
  private readonly catalogState = signal<ModuleCatalog | null>(null);
  private readonly scriptLoads = new Map<string, Promise<void>>();
  private pendingLoad: Observable<ModuleCatalog> | null = null;

  readonly catalog = this.catalogState.asReadonly();
  readonly navigation = computed<ModuleWebContribution[]>(() =>
    (this.catalogState()?.modules ?? [])
      .filter((module) => module.state === 'ACTIVE')
      .flatMap((module) =>
        module.webContributions.map((contribution) => ({ ...contribution, moduleId: module.id }))
      )
      .sort((left, right) => left.order - right.order || left.label.localeCompare(right.label))
  );
  readonly botSettings = computed<ModuleBotSettingsContribution[]>(() =>
    (this.catalogState()?.modules ?? [])
      .filter((module) => module.state === 'ACTIVE')
      .flatMap((module) =>
        module.botSettingsContributions.map((contribution) => ({
          ...contribution,
          moduleId: module.id,
        })),
      )
      .sort((left, right) => left.order - right.order || left.label.localeCompare(right.label)),
  );

  load(force = false): Observable<ModuleCatalog> {
    if (!force && this.pendingLoad) {
      return this.pendingLoad;
    }
    const request = this.http.get<ModuleCatalog>('/api/modules').pipe(
      tap((catalog) => this.catalogState.set(catalog)),
      finalize(() => {
        if (this.pendingLoad === request) {
          this.pendingLoad = null;
        }
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    this.pendingLoad = request;
    return request;
  }

  clear(): void {
    this.catalogState.set(null);
    this.pendingLoad = null;
  }

  findContribution(moduleId: string, contributionId: string): ModuleWebContribution | null {
    return (
      this.navigation().find(
        (contribution) =>
          contribution.moduleId === moduleId && contribution.id === contributionId
      ) ?? null
    );
  }

  loadWebComponent(
    contribution: ModuleWebContribution | ModuleBotSettingsContribution,
  ): Promise<void> {
    if (!contribution.entrypoint || !contribution.customElement) {
      return Promise.reject(new Error('Module Web contribution has no external renderer'));
    }
    if (customElements.get(contribution.customElement)) {
      return Promise.resolve();
    }
    const existing = this.scriptLoads.get(contribution.entrypoint);
    if (existing) {
      return existing;
    }
    const loading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.type = 'module';
      script.src = contribution.entrypoint!;
      script.addEventListener('load', () => {
        customElements.whenDefined(contribution.customElement!).then(() => resolve(), reject);
      });
      script.addEventListener('error', () => reject(new Error('Module Web asset could not be loaded')));
      document.head.appendChild(script);
    });
    this.scriptLoads.set(contribution.entrypoint, loading);
    loading.catch(() => this.scriptLoads.delete(contribution.entrypoint!));
    return loading;
  }
}
