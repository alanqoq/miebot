import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import type { DatabaseType } from './system-api.service';

export type OnboardingStage = 'ADMIN' | 'DATABASE' | 'BOT' | 'COMPLETE';

export interface OnboardingStatus {
  stage: OnboardingStage;
  databaseType: DatabaseType | null;
  botCount: number;
  completedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class OnboardingApiService {
  private readonly http = inject(HttpClient);
  private readonly statusState = signal<OnboardingStatus | null>(null);
  private readonly resourceUrl = '/api/system/onboarding';

  readonly currentStatus = this.statusState.asReadonly();

  status(): Observable<OnboardingStatus> {
    return this.http
      .get<OnboardingStatus>(this.resourceUrl)
      .pipe(tap((status) => this.statusState.set(status)));
  }

  databaseConfigured(
    expectedRevision: number,
    databaseType: DatabaseType
  ): Observable<OnboardingStatus> {
    return this.http
      .post<OnboardingStatus>(`${this.resourceUrl}/database-configured`, {
        expectedRevision,
        databaseType
      })
      .pipe(tap((status) => this.statusState.set(status)));
  }

  complete(): Observable<OnboardingStatus> {
    return this.http
      .post<OnboardingStatus>(`${this.resourceUrl}/complete`, {})
      .pipe(tap((status) => this.statusState.set(status)));
  }

  clear(): void {
    this.statusState.set(null);
  }
}
