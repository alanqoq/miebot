import { Injectable, computed, signal } from '@angular/core';
import type { DatabaseConfiguration, DatabaseType } from './system-api.service';

@Injectable({ providedIn: 'root' })
export class DatabaseStatusStore {
  private readonly configurationState = signal<DatabaseConfiguration | null>(null);

  readonly configuration = this.configurationState.asReadonly();
  readonly activeType = computed<DatabaseType | null>(() => this.configurationState()?.type ?? null);

  setConfiguration(configuration: DatabaseConfiguration): void {
    this.configurationState.set(configuration);
  }

  clear(): void {
    this.configurationState.set(null);
  }
}
