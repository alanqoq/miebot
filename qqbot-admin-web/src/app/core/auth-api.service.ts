import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { finalize, shareReplay, switchMap, tap } from 'rxjs/operators';
import { AuthSessionStore, AuthStatus } from './auth-session.store';

export interface SetupAdminRequest {
  username: string;
  password: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

@Injectable({ providedIn: 'root' })
export class AuthApiService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(AuthSessionStore);
  private readonly resourceUrl = '/api/auth';
  private statusRequest: Observable<AuthStatus> | null = null;

  readonly currentStatus = this.session.status;

  status(): Observable<AuthStatus> {
    return this.http
      .get<AuthStatus>(`${this.resourceUrl}/status`)
      .pipe(tap((status) => this.session.setStatus(status)));
  }

  ensureStatus(): Observable<AuthStatus> {
    const current = this.currentStatus();
    if (current) {
      return of(current);
    }
    if (this.statusRequest) {
      return this.statusRequest;
    }

    const request = this.status().pipe(
      finalize(() => {
        this.statusRequest = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    );
    this.statusRequest = request;
    return request;
  }

  setup(request: SetupAdminRequest): Observable<AuthStatus> {
    return this.http
      .post<AuthStatus>(`${this.resourceUrl}/setup`, request)
      .pipe(switchMap(() => this.status()));
  }

  login(request: LoginRequest): Observable<AuthStatus> {
    return this.http
      .post<AuthStatus>(`${this.resourceUrl}/login`, request)
      .pipe(switchMap(() => this.status()));
  }

  logout(): Observable<AuthStatus> {
    return this.http
      .post<AuthStatus>(`${this.resourceUrl}/logout`, null)
      .pipe(switchMap(() => this.status()));
  }

  changePassword(request: ChangePasswordRequest): Observable<AuthStatus> {
    return this.http.post<AuthStatus>(`${this.resourceUrl}/password`, request)
      .pipe(tap((status) => this.session.setStatus(status)));
  }
}
