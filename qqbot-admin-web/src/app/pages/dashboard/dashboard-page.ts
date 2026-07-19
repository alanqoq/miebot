import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, forkJoin, of, timer } from 'rxjs';
import { catchError, exhaustMap, finalize } from 'rxjs/operators';
import {
  LucideActivity,
  LucideBot,
  LucideCheckCircle,
  LucideClock,
  LucideInbox,
  LucidePuzzle,
  LucideRefreshCw,
  LucideServer,
} from '@lucide/angular';
import { BotApiService, BotRuntimeSummary } from '../../core/bot-api.service';
import {
  EventApiService,
  InboxEventSummary,
  OutboxQueueStats,
} from '../../core/event-api.service';
import { PluginApiService, PluginInventory } from '../../core/plugin-api.service';
import { HealthStatus, SystemApiService, SystemInfo } from '../../core/system-api.service';

@Component({
  selector: 'app-dashboard-page',
  imports: [
    DatePipe,
    LucideActivity,
    LucideBot,
    LucideCheckCircle,
    LucideClock,
    LucideInbox,
    LucidePuzzle,
    LucideRefreshCw,
    LucideServer,
  ],
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.scss',
})
export class DashboardPage implements OnInit {
  private readonly systemApi = inject(SystemApiService);
  private readonly botApi = inject(BotApiService);
  private readonly eventApi = inject(EventApiService);
  private readonly pluginApi = inject(PluginApiService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly platformRefreshing = signal(false);
  private readonly runtimeRefreshing = signal(false);
  private readonly operationsRefreshing = signal(false);
  protected readonly refreshing = computed(
    () => this.platformRefreshing() || this.runtimeRefreshing() || this.operationsRefreshing(),
  );
  protected readonly systemInfo = signal<SystemInfo | null>(null);
  protected readonly liveness = signal<HealthStatus | null>(null);
  protected readonly readiness = signal<HealthStatus | null>(null);
  protected readonly runtimeSummary = signal<BotRuntimeSummary | null>(null);
  protected readonly connectionFailed = signal(false);
  protected readonly runtimeFailed = signal(false);
  protected readonly operationsFailed = signal(false);
  protected readonly operationsPartialMessage = signal<string | null>(null);
  protected readonly outboxStats = signal<OutboxQueueStats | null>(null);
  protected readonly pluginInventory = signal<PluginInventory | null>(null);
  protected readonly recentEvents = signal<InboxEventSummary[]>([]);
  protected readonly pendingTaskCount = computed(() => {
    const stats = this.outboxStats();
    return stats === null
      ? null
      : stats.pendingCount + stats.inProgressCount + stats.retryWaitCount;
  });
  protected readonly pluginBindingCount = computed(() => {
    const inventory = this.pluginInventory();
    return inventory === null
      ? null
      : inventory.items.reduce((count, item) => count + item.enabledBindingCount, 0);
  });
  protected readonly pluginArtifactCount = computed(() => this.pluginInventory()?.items.length ?? null);

  ngOnInit(): void {
    this.refresh();
    timer(5000, 5000)
      .pipe(
        exhaustMap(() =>
          this.botApi.getRuntimeSummary().pipe(
            catchError(() => {
              this.markRuntimeFailed();
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((summary) => this.applyRuntimeSummary(summary));
    timer(10000, 10000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.loadOperations(false));
  }

  protected refresh(): void {
    this.refreshPlatform();
    this.refreshRuntime();
    this.loadOperations(true);
  }

  private refreshPlatform(): void {
    this.platformRefreshing.set(true);
    this.connectionFailed.set(false);
    forkJoin({
      info: this.systemApi.getSystemInfo(),
      liveness: this.systemApi.getLiveness(),
      readiness: this.systemApi.getReadiness(),
    })
      .pipe(finalize(() => this.platformRefreshing.set(false)))
      .subscribe({
        next: ({ info, liveness, readiness }) => {
          this.systemInfo.set(info);
          this.liveness.set(liveness);
          this.readiness.set(readiness);
        },
        error: () => {
          this.systemInfo.set(null);
          this.liveness.set(null);
          this.readiness.set(null);
          this.connectionFailed.set(true);
        },
      });
  }

  private refreshRuntime(): void {
    this.runtimeRefreshing.set(true);
    this.runtimeFailed.set(false);
    this.botApi
      .getRuntimeSummary()
      .pipe(finalize(() => this.runtimeRefreshing.set(false)))
      .subscribe({
        next: (summary) => this.applyRuntimeSummary(summary),
        error: () => this.markRuntimeFailed(),
      });
  }

  private applyRuntimeSummary(summary: BotRuntimeSummary): void {
    this.runtimeSummary.set(summary);
    this.runtimeFailed.set(false);
  }

  private markRuntimeFailed(): void {
    this.runtimeSummary.set(null);
    this.runtimeFailed.set(true);
  }

  private loadOperations(manual: boolean): void {
    if (this.operationsRefreshing()) {
      return;
    }
    this.operationsRefreshing.set(true);
    if (manual) {
      this.operationsFailed.set(false);
    }
    forkJoin({
      outbox: this.eventApi.getOutboxStats().pipe(catchError(() => of(null))),
      inbox: this.eventApi.listInbox({ limit: 5 }).pipe(catchError(() => of(null))),
      plugins:
        manual || this.pluginInventory() === null
          ? this.pluginApi.list().pipe(catchError(() => of(null)))
          : of(this.pluginInventory()),
    })
      .pipe(finalize(() => this.operationsRefreshing.set(false)))
      .subscribe(({ outbox, inbox, plugins }) => {
        const failed = outbox === null || inbox === null || plugins === null;
        this.operationsFailed.set(failed);
        const unavailable: string[] = [];
        if (outbox === null) unavailable.push('任务统计');
        if (inbox === null) unavailable.push('最近事件');
        if (plugins === null) unavailable.push('插件统计');
        this.operationsPartialMessage.set(
          unavailable.length > 0 ? `${unavailable.join('、')}暂不可用` : null,
        );
        if (outbox !== null) {
          this.outboxStats.set(outbox);
        }
        if (inbox !== null) {
          this.recentEvents.set(inbox.items);
        }
        if (plugins !== null) {
          this.pluginInventory.set(plugins);
        }
      });
  }
}
