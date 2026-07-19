import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideBot,
  LucideInbox,
  LucideLayoutDashboard,
  LucideLogOut,
  LucideMenu,
  LucidePuzzle,
  LucideSettings,
  LucideX
} from '@lucide/angular';
import { catchError, finalize } from 'rxjs/operators';
import { EMPTY, filter, map, timer } from 'rxjs';
import { AuthApiService } from './core/auth-api.service';
import { DatabaseStatusStore } from './core/database-status.store';
import { OnboardingApiService } from './core/onboarding-api.service';
import { SystemApiService } from './core/system-api.service';

@Component({
  selector: 'app-root',
  imports: [
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    LucideBot,
    LucideInbox,
    LucideLayoutDashboard,
    LucideLogOut,
    LucideMenu,
    LucidePuzzle,
    LucideSettings,
    LucideX
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  private readonly auth = inject(AuthApiService);
  private readonly router = inject(Router);
  private readonly databaseStatus = inject(DatabaseStatusStore);
  private readonly onboarding = inject(OnboardingApiService);
  private readonly system = inject(SystemApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects)
    ),
    { initialValue: this.router.url }
  );

  protected readonly authStatus = this.auth.currentStatus;
  protected readonly navigationOpen = signal(false);
  protected readonly loggingOut = signal(false);
  protected readonly logoutError = signal<string | null>(null);
  protected readonly serviceReady = signal<boolean | null>(null);
  protected readonly databaseStatusFailed = signal(false);
  protected readonly setupRoute = computed(() => this.currentUrl().startsWith('/setup'));
  protected readonly adminInitial = computed(() =>
    (this.authStatus()?.username?.trim().charAt(0) || 'A').toUpperCase()
  );
  protected readonly databaseLabel = computed(() => {
    const configuration = this.databaseStatus.configuration();
    if (!configuration) {
      return this.databaseStatusFailed() ? 'Database / Unavailable' : 'Database / Pending';
    }
    const label =
      configuration.type === 'POSTGRESQL'
        ? 'PostgreSQL'
        : configuration.type === 'MYSQL'
          ? 'MySQL'
          : 'SQLite';
    return `${label} / ${configuration.type === 'SQLITE' ? 'Local' : 'External'}`;
  });

  constructor() {
    effect(() => {
      const shouldCheck = this.authStatus()?.authenticated === true && !this.setupRoute();
      if (shouldCheck) {
        queueMicrotask(() => {
          this.refreshServiceStatus();
          this.refreshDatabaseStatus();
        });
      } else {
        this.serviceReady.set(null);
        this.databaseStatusFailed.set(false);
        if (!this.authStatus()?.authenticated) {
          this.databaseStatus.clear();
        }
      }
    });
  }

  ngOnInit(): void {
    timer(15000, 15000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshServiceStatus());
  }

  protected toggleNavigation(): void {
    this.navigationOpen.update((open) => !open);
  }

  protected closeNavigation(): void {
    this.navigationOpen.set(false);
  }

  protected logout(): void {
    if (this.loggingOut()) {
      return;
    }
    this.logoutError.set(null);
    this.loggingOut.set(true);
    this.auth
      .logout()
      .pipe(finalize(() => this.loggingOut.set(false)))
      .subscribe({
        next: () => {
          this.databaseStatus.clear();
          this.onboarding.clear();
          this.closeNavigation();
          void this.router.navigate(['/login']);
        },
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 401) {
            void this.router.navigate(['/login']);
            return;
          }
          this.logoutError.set(
            error instanceof HttpErrorResponse && error.status === 0
              ? '无法连接管理服务，注销尚未完成。'
              : '注销失败，请稍后重试。'
          );
        }
      });
  }

  private refreshServiceStatus(): void {
    if (!this.authStatus()?.authenticated || this.setupRoute()) {
      this.serviceReady.set(null);
      return;
    }
    this.system
      .getReadiness()
      .pipe(
        catchError(() => {
          this.serviceReady.set(false);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((readiness) => this.serviceReady.set(readiness.status === 'UP'));
  }

  private refreshDatabaseStatus(): void {
    if (!this.authStatus()?.authenticated || this.setupRoute()) {
      this.databaseStatusFailed.set(false);
      return;
    }
    this.system
      .ensureDatabaseConfiguration()
      .pipe(
        catchError(() => {
          this.databaseStatusFailed.set(true);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((configuration) => {
        this.databaseStatus.setConfiguration(configuration);
        this.databaseStatusFailed.set(false);
      });
  }
}
