import { Injectable, signal } from '@angular/core';

export interface AuthStatus {
  setupRequired: boolean;
  authenticated: boolean;
  username: string | null;
}

@Injectable({ providedIn: 'root' })
export class AuthSessionStore {
  private readonly statusState = signal<AuthStatus | null>(null);

  readonly status = this.statusState.asReadonly();

  setStatus(status: AuthStatus): void {
    this.statusState.set(status);
  }

  markUnauthenticated(): void {
    const current = this.statusState();
    this.statusState.set({
      setupRequired: current?.setupRequired ?? false,
      authenticated: false,
      username: null
    });
  }
}
