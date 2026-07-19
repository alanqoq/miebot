import { DatePipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  LucideAlertTriangle,
  LucideArchiveX,
  LucideCheck,
  LucideCircleAlert,
  LucideCopy,
  LucideEye,
  LucideFileJson,
  LucideFilter,
  LucideInbox,
  LucideRefreshCw,
  LucideSearch,
  LucideX,
} from '@lucide/angular';
import { forkJoin, of, Subject, timer } from 'rxjs';
import { catchError, debounceTime, finalize } from 'rxjs/operators';
import {
  EventApiService,
  InboxEnvironment,
  InboxEventDetail,
  InboxEventPage,
  InboxEventSummary,
  InboxQuery,
  InboxStatus,
  OutboxJobDetail,
  OutboxJobPage,
  OutboxJobSummary,
  OutboxQuery,
  OutboxQueueStats,
  OutboxStatus,
} from '../../core/event-api.service';

type EventView = 'inbox' | 'outbox' | 'dlq';
type DeliveryView = 'outbox' | 'dlq';
type EnvironmentFilter = InboxEnvironment | 'ALL';
type StatusFilter = InboxStatus | 'ALL';

const PAGE_SIZE = 50;

const OUTBOX_STATUSES: readonly OutboxStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'RETRY_WAIT',
  'SUCCEEDED',
  'RESULT_UNKNOWN',
  'DEAD_LETTER',
];

@Component({
  selector: 'app-events-page',
  imports: [
    DatePipe,
    LucideAlertTriangle,
    LucideArchiveX,
    LucideCheck,
    LucideCircleAlert,
    LucideCopy,
    LucideEye,
    LucideFileJson,
    LucideFilter,
    LucideInbox,
    LucideRefreshCw,
    LucideSearch,
    LucideX,
  ],
  templateUrl: './events-page.html',
  styleUrl: './events-page.scss',
})
export class EventsPage implements OnInit {
  private readonly api = inject(EventApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly searchChanged = new Subject<string>();
  private requestGeneration = 0;
  private outboxRequestGeneration = 0;
  private dlqRequestGeneration = 0;
  private detailRequestGeneration = 0;
  private detailTrigger: HTMLElement | null = null;

  @ViewChild('detailDialog')
  private detailDialog?: ElementRef<HTMLElement>;

  @ViewChild('dialogClose')
  private dialogClose?: ElementRef<HTMLButtonElement>;

  @ViewChild('jobDialog')
  private jobDialog?: ElementRef<HTMLElement>;

  @ViewChild('jobDialogClose')
  private jobDialogClose?: ElementRef<HTMLButtonElement>;

  protected readonly activeView = signal<EventView>('inbox');
  protected readonly items = signal<InboxEventSummary[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly hasMore = signal(false);
  protected readonly observedAt = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly refreshing = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly loaded = signal(false);
  protected readonly pageError = signal<string | null>(null);
  protected readonly refreshWarning = signal<string | null>(null);
  protected readonly loadMoreError = signal<string | null>(null);

  protected readonly outboxItems = signal<OutboxJobSummary[]>([]);
  protected readonly outboxNextCursor = signal<string | null>(null);
  protected readonly outboxHasMore = signal(false);
  protected readonly outboxObservedAt = signal<string | null>(null);
  protected readonly outboxStats = signal<OutboxQueueStats | null>(null);
  protected readonly outboxLoading = signal(false);
  protected readonly outboxRefreshing = signal(false);
  protected readonly outboxLoadingMore = signal(false);
  protected readonly outboxLoaded = signal(false);
  protected readonly outboxError = signal<string | null>(null);
  protected readonly outboxWarning = signal<string | null>(null);
  protected readonly outboxLoadMoreError = signal<string | null>(null);

  protected readonly dlqItems = signal<OutboxJobSummary[]>([]);
  protected readonly dlqNextCursor = signal<string | null>(null);
  protected readonly dlqHasMore = signal(false);
  protected readonly dlqObservedAt = signal<string | null>(null);
  protected readonly dlqStats = signal<OutboxQueueStats | null>(null);
  protected readonly dlqLoading = signal(false);
  protected readonly dlqRefreshing = signal(false);
  protected readonly dlqLoadingMore = signal(false);
  protected readonly dlqLoaded = signal(false);
  protected readonly dlqError = signal<string | null>(null);
  protected readonly dlqWarning = signal<string | null>(null);
  protected readonly dlqLoadMoreError = signal<string | null>(null);

  protected readonly selectedJobId = signal<string | null>(null);
  protected readonly selectedJobView = signal<'outbox' | 'dlq' | null>(null);
  protected readonly jobDetail = signal<OutboxJobDetail | null>(null);
  protected readonly jobDetailLoading = signal(false);
  protected readonly jobDetailError = signal<string | null>(null);
  private jobDetailRequestGeneration = 0;

  protected readonly search = signal('');
  protected readonly botFilter = signal('');
  protected readonly environmentFilter = signal<EnvironmentFilter>('ALL');
  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly jobStatusFilter = signal<OutboxStatus | 'ALL'>('ALL');

  protected readonly selectedId = signal<string | null>(null);
  protected readonly detail = signal<InboxEventDetail | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);
  protected readonly copyStatus = signal<string | null>(null);
  protected readonly jobCopyStatus = signal<string | null>(null);

  protected readonly canLoadMore = computed(() => this.hasMore() && Boolean(this.nextCursor()));
  protected readonly outboxCanLoadMore = computed(
    () => this.outboxHasMore() && Boolean(this.outboxNextCursor()),
  );
  protected readonly dlqCanLoadMore = computed(
    () => this.dlqHasMore() && Boolean(this.dlqNextCursor()),
  );
  protected readonly outboxPendingCount = computed(() => {
    const stats = this.outboxStats();
    return stats === null
      ? null
      : stats.pendingCount + stats.inProgressCount + stats.retryWaitCount;
  });
  protected readonly dlqCount = computed(() => this.dlqStats()?.deadLetterCount ?? null);
  protected readonly hasActiveFilters = computed(
    () =>
      Boolean(this.search().trim()) ||
      Boolean(this.botFilter().trim()) ||
      this.environmentFilter() !== 'ALL' ||
      this.statusFilter() !== 'ALL',
  );
  protected readonly formattedPayload = computed(() => {
    return this.formatPayload(this.detail()?.payload ?? '');
  });

  protected readonly formattedJobPayload = computed(() =>
    this.formatPayload(this.jobDetail()?.payload ?? ''),
  );

  ngOnInit(): void {
    this.loadInbox();
    this.loadQueueStats();
    this.searchChanged
      .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.activeView() === 'inbox') {
          this.loadInbox();
        } else {
          this.loadDelivery(this.toDeliveryView(this.activeView()));
        }
      });
    timer(5000, 5000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.activeView() === 'inbox' && this.items().length <= PAGE_SIZE) {
          this.refresh(true);
          this.loadQueueStats();
        } else if (this.activeView() === 'outbox' && this.outboxItems().length <= PAGE_SIZE) {
          this.refreshDelivery('outbox', true);
        } else if (this.activeView() === 'dlq' && this.dlqItems().length <= PAGE_SIZE) {
          this.refreshDelivery('dlq', true);
        }
      });
  }

  protected selectView(view: EventView): void {
    this.activeView.set(view);
    if (view === 'inbox') {
      this.jobStatusFilter.set('ALL');
    } else {
      this.statusFilter.set('ALL');
    }
    if (view === 'inbox' && !this.loaded()) {
      this.loadInbox();
    } else if (view === 'outbox' && !this.outboxLoaded()) {
      this.loadDelivery('outbox');
    } else if (view === 'dlq' && !this.dlqLoaded()) {
      this.loadDelivery('dlq');
    } else if (this.hasActiveFilters() || this.jobStatusFilter() !== 'ALL') {
      if (view === 'inbox') {
        this.loadInbox();
      } else {
        this.loadDelivery(view);
      }
    }
  }

  protected refresh(background = false): void {
    if (this.activeView() !== 'inbox') {
      this.refreshDelivery(this.toDeliveryView(this.activeView()), background);
      return;
    }
    if (this.loading() || this.refreshing() || this.loadingMore()) {
      return;
    }
    if (!this.loaded()) {
      this.loadInbox();
      return;
    }
    this.requestPage(false, background);
  }

  protected loadInbox(): void {
    if (this.activeView() !== 'inbox') {
      return;
    }
    this.requestPage(false, false);
  }

  protected loadMore(): void {
    if (this.activeView() !== 'inbox') {
      this.loadMoreDelivery(this.toDeliveryView(this.activeView()));
      return;
    }
    if (!this.nextCursor() || this.loading() || this.refreshing() || this.loadingMore()) {
      return;
    }
    this.requestPage(true, false);
  }

  protected updateSearch(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.search.set(value);
    if (this.activeView() === 'inbox') {
      this.searchChanged.next(value.trim());
    } else {
      this.searchChanged.next(`delivery:${value.trim()}`);
    }
  }

  protected updateBotFilter(event: Event): void {
    this.botFilter.set((event.target as HTMLInputElement).value);
  }

  protected applyBotFilter(): void {
    if (this.activeView() === 'inbox') {
      this.loadInbox();
    } else {
      this.loadDelivery(this.toDeliveryView(this.activeView()));
    }
  }

  protected updateEnvironment(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.environmentFilter.set(
      value === 'SANDBOX' || value === 'PRODUCTION' ? value : 'ALL',
    );
    if (this.activeView() === 'inbox') {
      this.loadInbox();
    } else {
      this.loadDelivery(this.toDeliveryView(this.activeView()));
    }
  }

  protected updateStatus(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.statusFilter.set(
      value === 'RECEIVED' ||
        value === 'PROCESSING' ||
        value === 'DISPATCHED' ||
        value === 'DEAD_LETTER'
        ? value
        : 'ALL',
    );
    if (this.activeView() === 'inbox') {
      this.loadInbox();
    } else {
      this.loadDelivery(this.toDeliveryView(this.activeView()));
    }
  }

  protected updateJobStatus(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.jobStatusFilter.set(
      OUTBOX_STATUSES.includes(value as OutboxStatus) ? (value as OutboxStatus) : 'ALL',
    );
    this.loadDelivery(this.activeView() === 'dlq' ? 'dlq' : 'outbox');
  }

  protected clearFilters(): void {
    this.search.set('');
    this.botFilter.set('');
    this.environmentFilter.set('ALL');
    this.statusFilter.set('ALL');
    this.jobStatusFilter.set('ALL');
    if (this.activeView() === 'inbox') {
      this.loadInbox();
    } else {
      this.loadDelivery(this.toDeliveryView(this.activeView()));
    }
  }

  protected openDetail(event: InboxEventSummary): void {
    this.selectedJobId.set(null);
    this.selectedJobView.set(null);
    this.jobDetailRequestGeneration++;
    const requestId = ++this.detailRequestGeneration;
    this.detailTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.selectedId.set(event.id);
    this.detail.set(null);
    this.detailError.set(null);
    this.copyStatus.set(null);
    this.detailLoading.set(true);
    queueMicrotask(() => this.dialogClose?.nativeElement.focus());
    this.api
      .getInboxEvent(event.id)
      .pipe(
        finalize(() => {
          if (requestId === this.detailRequestGeneration) {
            this.detailLoading.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (detail) => {
          if (requestId === this.detailRequestGeneration && this.selectedId() === event.id) {
            this.detail.set(detail);
          }
        },
        error: (error: unknown) => {
          if (requestId === this.detailRequestGeneration && this.selectedId() === event.id) {
            this.detailError.set(this.errorMessage(error, '无法加载事件详情，请稍后重试。'));
          }
        },
      });
  }

  protected openJobDetail(job: OutboxJobSummary, view: EventView): void {
    const deliveryView = this.toDeliveryView(view);
    this.selectedId.set(null);
    this.detailRequestGeneration++;
    const requestId = ++this.jobDetailRequestGeneration;
    this.detailTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.selectedJobId.set(job.id);
    this.selectedJobView.set(deliveryView);
    this.jobDetail.set(null);
    this.jobDetailError.set(null);
    this.jobCopyStatus.set(null);
    this.jobDetailLoading.set(true);
    queueMicrotask(() => this.jobDialogClose?.nativeElement.focus());
    const request$ = deliveryView === 'outbox'
      ? this.api.getOutboxJob(job.id)
      : this.api.getDlqJob(job.id);
    request$
      .pipe(
        finalize(() => {
          if (requestId === this.jobDetailRequestGeneration) {
            this.jobDetailLoading.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (detail) => {
          if (requestId === this.jobDetailRequestGeneration && this.selectedJobId() === job.id) {
            this.jobDetail.set(detail);
          }
        },
        error: (error: unknown) => {
          if (requestId === this.jobDetailRequestGeneration && this.selectedJobId() === job.id) {
            this.jobDetailError.set(this.errorMessage(error, '无法加载任务详情，请稍后重试。'));
          }
        },
      });
  }

  protected retryDetail(): void {
    const id = this.selectedId();
    const summary = id ? this.items().find((item) => item.id === id) : undefined;
    if (summary) {
      this.openDetail(summary);
    }
  }

  protected retryJobDetail(): void {
    const id = this.selectedJobId();
    const view = this.selectedJobView();
    if (!id || !view) {
      return;
    }
    const source = view === 'outbox' ? this.outboxItems() : this.dlqItems();
    const summary = source.find((item) => item.id === id);
    if (summary) {
      this.openJobDetail(summary, view);
    }
  }

  protected closeDetail(): void {
    const trigger = this.detailTrigger;
    this.detailTrigger = null;
    this.detailRequestGeneration++;
    this.selectedId.set(null);
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(false);
    this.copyStatus.set(null);
    queueMicrotask(() => {
      if (trigger?.isConnected) {
        trigger.focus();
      }
    });
  }

  protected closeJobDetail(): void {
    const trigger = this.detailTrigger;
    this.detailTrigger = null;
    this.jobDetailRequestGeneration++;
    this.selectedJobId.set(null);
    this.selectedJobView.set(null);
    this.jobDetail.set(null);
    this.jobDetailError.set(null);
    this.jobDetailLoading.set(false);
    this.jobCopyStatus.set(null);
    queueMicrotask(() => {
      if (trigger?.isConnected) {
        trigger.focus();
      }
    });
  }

  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closeDetail();
    }
  }

  protected async copyPayload(): Promise<void> {
    const payload = this.formattedPayload();
    if (!payload) {
      return;
    }
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API unavailable');
      }
      await navigator.clipboard.writeText(payload);
      this.copyStatus.set('已复制');
    } catch {
      this.copyStatus.set('复制失败');
    }
  }

  protected async copyJobPayload(): Promise<void> {
    const payload = this.formatPayload(this.jobDetail()?.payload ?? '');
    if (!payload) {
      return;
    }
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API unavailable');
      }
      await navigator.clipboard.writeText(payload);
      this.jobCopyStatus.set('已复制');
    } catch {
      this.jobCopyStatus.set('复制失败');
    }
  }

  protected statusLabel(status: InboxStatus): string {
    switch (status) {
      case 'RECEIVED':
        return '已接收';
      case 'PROCESSING':
        return '处理中';
      case 'DISPATCHED':
        return '已分发';
      case 'DEAD_LETTER':
        return '死信';
    }
  }

  protected statusClass(status: InboxStatus): string {
    switch (status) {
      case 'RECEIVED':
        return 'status-received';
      case 'PROCESSING':
        return 'status-processing';
      case 'DISPATCHED':
        return 'status-dispatched';
      case 'DEAD_LETTER':
        return 'status-dead-letter';
    }
  }

  protected jobStatusLabel(status: OutboxStatus): string {
    switch (status) {
      case 'PENDING':
        return '待处理';
      case 'IN_PROGRESS':
        return '处理中';
      case 'RETRY_WAIT':
        return '等待重试';
      case 'SUCCEEDED':
        return '已完成';
      case 'RESULT_UNKNOWN':
        return '结果未知';
      case 'DEAD_LETTER':
        return '死信';
    }
  }

  protected jobStatusClass(status: OutboxStatus): string {
    switch (status) {
      case 'PENDING':
        return 'status-received';
      case 'IN_PROGRESS':
        return 'status-processing';
      case 'RETRY_WAIT':
        return 'status-processing';
      case 'SUCCEEDED':
        return 'status-dispatched';
      case 'RESULT_UNKNOWN':
      case 'DEAD_LETTER':
        return 'status-dead-letter';
    }
  }

  protected deliveryViewLabel(view: EventView): string {
    return view === 'outbox' ? 'Outbox' : 'DLQ';
  }

  protected deliveryItems(view: EventView): OutboxJobSummary[] {
    return view === 'outbox' ? this.outboxItems() : this.dlqItems();
  }

  protected deliveryLoadingState(view: EventView): boolean {
    return view === 'outbox' ? this.outboxLoading() : this.dlqLoading();
  }

  protected deliveryRefreshingState(view: EventView): boolean {
    return view === 'outbox' ? this.outboxRefreshing() : this.dlqRefreshing();
  }

  protected deliveryLoadingMoreState(view: EventView): boolean {
    return view === 'outbox' ? this.outboxLoadingMore() : this.dlqLoadingMore();
  }

  protected deliveryError(view: EventView): string | null {
    return view === 'outbox' ? this.outboxError() : this.dlqError();
  }

  protected deliveryWarning(view: EventView): string | null {
    return view === 'outbox' ? this.outboxWarning() : this.dlqWarning();
  }

  protected deliveryLoadMoreError(view: EventView): string | null {
    return view === 'outbox' ? this.outboxLoadMoreError() : this.dlqLoadMoreError();
  }

  protected deliveryCanLoadMore(view: EventView): boolean {
    return view === 'outbox' ? this.outboxCanLoadMore() : this.dlqCanLoadMore();
  }

  protected deliveryObservedAt(view: EventView): string | null {
    return view === 'outbox' ? this.outboxObservedAt() : this.dlqObservedAt();
  }

  protected viewLoading(): boolean {
    switch (this.activeView()) {
      case 'inbox':
        return this.loading();
      case 'outbox':
        return this.outboxLoading();
      case 'dlq':
        return this.dlqLoading();
    }
  }

  protected viewRefreshing(): boolean {
    switch (this.activeView()) {
      case 'inbox':
        return this.refreshing();
      case 'outbox':
        return this.outboxRefreshing();
      case 'dlq':
        return this.dlqRefreshing();
    }
  }

  protected viewLoadingMore(): boolean {
    switch (this.activeView()) {
      case 'inbox':
        return this.loadingMore();
      case 'outbox':
        return this.outboxLoadingMore();
      case 'dlq':
        return this.dlqLoadingMore();
    }
  }

  protected deliveryStats(view: EventView): OutboxQueueStats | null {
    return view === 'outbox' ? this.outboxStats() : this.dlqStats();
  }

  protected deliveryActiveCount(stats: OutboxQueueStats): number {
    return stats.pendingCount + stats.inProgressCount + stats.retryWaitCount;
  }

  protected environmentLabel(environment: InboxEnvironment): string {
    return environment === 'PRODUCTION' ? '正式' : '沙箱';
  }

  protected filterQueryValue(): string {
    return this.hasActiveFilters() ? '已应用筛选' : '全部事件';
  }

  @HostListener('document:keydown', ['$event'])
  protected handleDocumentKeydown(event: KeyboardEvent): void {
    if (!this.selectedId() && !this.selectedJobId()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      if (this.selectedJobId()) {
        this.closeJobDetail();
      } else {
        this.closeDetail();
      }
      return;
    }
    if (event.key === 'Tab') {
      this.trapDialogFocus(event, this.selectedJobId() ? this.jobDialog : this.detailDialog);
    }
  }

  private trapDialogFocus(
    event: KeyboardEvent,
    dialogRef?: ElementRef<HTMLElement>,
  ): void {
    const dialog = dialogRef?.nativeElement;
    if (!dialog) {
      return;
    }
    const focusable = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((element) => element.getClientRects().length > 0);
    if (focusable.length === 0) {
      event.preventDefault();
      dialog.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !dialog.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !dialog.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  }

  private requestPage(append: boolean, background: boolean): void {
    const requestId = ++this.requestGeneration;
    const initialRequest = !append && !this.loaded();
    if (append) {
      this.loadingMore.set(true);
      this.loadMoreError.set(null);
    } else if (background || !initialRequest) {
      this.loadingMore.set(false);
      this.refreshing.set(true);
      this.refreshWarning.set(null);
      this.pageError.set(null);
    } else {
      this.loadingMore.set(false);
      this.loading.set(true);
      this.pageError.set(null);
      this.refreshWarning.set(null);
    }

    this.api
      .listInbox(this.buildQuery(append ? this.nextCursor() : null))
      .pipe(
        finalize(() => {
          if (requestId !== this.requestGeneration) {
            return;
          }
          if (append) {
            this.loadingMore.set(false);
          } else if (background || !initialRequest) {
            this.refreshing.set(false);
          } else {
            this.loading.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => {
          if (requestId !== this.requestGeneration) {
            return;
          }
          this.applyPage(page, append);
        },
        error: (error: unknown) => {
          if (requestId !== this.requestGeneration) {
            return;
          }
          const message = this.errorMessage(error, '无法加载 Inbox 事件，请稍后重试。');
          if (append) {
            this.loadMoreError.set(message);
          } else if (this.loaded() && this.items().length > 0) {
            this.refreshWarning.set(message);
          } else {
            this.pageError.set(message);
          }
        },
      });
  }

  private loadQueueStats(): void {
    forkJoin({
      outbox: this.api.getOutboxStats().pipe(catchError(() => of(null))),
      dlq: this.api.getDlqStats().pipe(catchError(() => of(null))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ outbox, dlq }) => {
        if (outbox !== null) {
          this.outboxStats.set(outbox);
        }
        if (dlq !== null) {
          this.dlqStats.set(dlq);
        }
      });
  }

  private refreshDelivery(view: 'outbox' | 'dlq', background: boolean): void {
    const loading = view === 'outbox' ? this.outboxLoading() : this.dlqLoading();
    const refreshing = view === 'outbox' ? this.outboxRefreshing() : this.dlqRefreshing();
    const loadingMore = view === 'outbox' ? this.outboxLoadingMore() : this.dlqLoadingMore();
    if (loading || refreshing || loadingMore) {
      return;
    }
    const loaded = view === 'outbox' ? this.outboxLoaded() : this.dlqLoaded();
    if (!loaded) {
      this.loadDelivery(view);
      return;
    }
    this.requestDeliveryPage(view, false, background);
  }

  private loadDelivery(view: 'outbox' | 'dlq'): void {
    this.requestDeliveryPage(view, false, false);
  }

  private loadMoreDelivery(view: 'outbox' | 'dlq'): void {
    const cursor = view === 'outbox' ? this.outboxNextCursor() : this.dlqNextCursor();
    const loading = view === 'outbox' ? this.outboxLoading() : this.dlqLoading();
    const refreshing = view === 'outbox' ? this.outboxRefreshing() : this.dlqRefreshing();
    const loadingMore = view === 'outbox' ? this.outboxLoadingMore() : this.dlqLoadingMore();
    if (!cursor || loading || refreshing || loadingMore) {
      return;
    }
    this.requestDeliveryPage(view, true, false);
  }

  private requestDeliveryPage(
    view: 'outbox' | 'dlq',
    append: boolean,
    background: boolean,
  ): void {
    const requestId = view === 'outbox'
      ? ++this.outboxRequestGeneration
      : ++this.dlqRequestGeneration;
    const loaded = view === 'outbox' ? this.outboxLoaded() : this.dlqLoaded();
    const cursor = view === 'outbox' ? this.outboxNextCursor() : this.dlqNextCursor();
    const setLoading = (value: boolean) =>
      view === 'outbox' ? this.outboxLoading.set(value) : this.dlqLoading.set(value);
    const setRefreshing = (value: boolean) =>
      view === 'outbox' ? this.outboxRefreshing.set(value) : this.dlqRefreshing.set(value);
    const setLoadingMore = (value: boolean) =>
      view === 'outbox' ? this.outboxLoadingMore.set(value) : this.dlqLoadingMore.set(value);
    const setError = (value: string | null) =>
      view === 'outbox' ? this.outboxError.set(value) : this.dlqError.set(value);
    const setWarning = (value: string | null) =>
      view === 'outbox' ? this.outboxWarning.set(value) : this.dlqWarning.set(value);
    const setMoreError = (value: string | null) =>
      view === 'outbox'
        ? this.outboxLoadMoreError.set(value)
        : this.dlqLoadMoreError.set(value);
    const request$ = view === 'outbox'
      ? this.api.listOutbox(this.buildDeliveryQuery(append ? cursor : null))
      : this.api.listDlq(this.buildDeliveryQuery(append ? cursor : null));

    if (append) {
      setLoadingMore(true);
      setMoreError(null);
    } else if (background || loaded) {
      // A fresh filtered request supersedes an in-flight load-more request.
      setLoadingMore(false);
      setRefreshing(true);
      setWarning(null);
      setError(null);
    } else {
      setLoading(true);
      setError(null);
      setWarning(null);
    }

    request$
      .pipe(
        finalize(() => {
          const currentRequest = view === 'outbox'
            ? this.outboxRequestGeneration
            : this.dlqRequestGeneration;
          if (requestId !== currentRequest) {
            return;
          }
          if (append) {
            setLoadingMore(false);
          } else if (background || loaded) {
            setRefreshing(false);
          } else {
            setLoading(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => {
          const currentRequest = view === 'outbox'
            ? this.outboxRequestGeneration
            : this.dlqRequestGeneration;
          if (requestId === currentRequest) {
            this.applyDeliveryPage(view, page, append);
          }
        },
        error: (error: unknown) => {
          const currentRequest = view === 'outbox'
            ? this.outboxRequestGeneration
            : this.dlqRequestGeneration;
          if (requestId !== currentRequest) {
            return;
          }
          const message = this.errorMessage(error, `无法加载 ${view === 'outbox' ? 'Outbox' : 'DLQ'} 任务，请稍后重试。`);
          const hasRows = view === 'outbox' ? this.outboxItems().length > 0 : this.dlqItems().length > 0;
          if (append) {
            setMoreError(message);
          } else if (loaded && hasRows) {
            setWarning(message);
          } else {
            setError(message);
          }
        },
      });
  }

  private applyDeliveryPage(view: 'outbox' | 'dlq', page: OutboxJobPage, append: boolean): void {
    const items = view === 'outbox' ? this.outboxItems : this.dlqItems;
    const setCursor = view === 'outbox' ? this.outboxNextCursor : this.dlqNextCursor;
    const setHasMore = view === 'outbox' ? this.outboxHasMore : this.dlqHasMore;
    const setObservedAt = view === 'outbox' ? this.outboxObservedAt : this.dlqObservedAt;
    const setStats = view === 'outbox' ? this.outboxStats : this.dlqStats;
    const setLoaded = view === 'outbox' ? this.outboxLoaded : this.dlqLoaded;
    const setError = view === 'outbox' ? this.outboxError : this.dlqError;
    const setWarning = view === 'outbox' ? this.outboxWarning : this.dlqWarning;
    const setMoreError = view === 'outbox' ? this.outboxLoadMoreError : this.dlqLoadMoreError;
    if (append) {
      items.update((current) => {
        const existing = new Set(current.map((item) => item.id));
        return [...current, ...page.items.filter((item) => !existing.has(item.id))];
      });
    } else {
      items.set(page.items);
    }
    setCursor.set(page.nextCursor);
    setHasMore.set(page.hasMore);
    setObservedAt.set(page.observedAt);
    setStats.set(page.stats);
    setLoaded.set(true);
    setError.set(null);
    setWarning.set(null);
    setMoreError.set(null);
  }

  private buildDeliveryQuery(cursor: string | null): OutboxQuery {
    const query: OutboxQuery = { limit: PAGE_SIZE };
    if (cursor) {
      query.cursor = cursor;
    }
    if (this.search().trim()) {
      query.query = this.search().trim();
    }
    if (this.botFilter().trim()) {
      query.botId = this.botFilter().trim();
    }
    const environment = this.environmentFilter();
    if (environment !== 'ALL') {
      query.environment = environment;
    }
    const status = this.jobStatusFilter();
    if (status !== 'ALL' && this.activeView() === 'outbox') {
      query.status = status;
    }
    return query;
  }

  private toDeliveryView(view: EventView): DeliveryView {
    return view === 'dlq' ? 'dlq' : 'outbox';
  }

  private applyPage(page: InboxEventPage, append: boolean): void {
    if (append) {
      this.items.update((current) => {
        const existing = new Set(current.map((item) => item.id));
        return [...current, ...page.items.filter((item) => !existing.has(item.id))];
      });
    } else {
      this.items.set(page.items);
    }
    this.nextCursor.set(page.nextCursor);
    this.hasMore.set(page.hasMore);
    this.observedAt.set(page.observedAt);
    this.loaded.set(true);
    this.pageError.set(null);
    this.refreshWarning.set(null);
    this.loadMoreError.set(null);
  }

  private buildQuery(cursor: string | null): InboxQuery {
    const query: InboxQuery = { limit: PAGE_SIZE };
    if (cursor) {
      query.cursor = cursor;
    }
    if (this.search().trim()) {
      query.query = this.search().trim();
    }
    if (this.botFilter().trim()) {
      query.botId = this.botFilter().trim();
    }
    const environment = this.environmentFilter();
    if (environment !== 'ALL') {
      query.environment = environment;
    }
    const status = this.statusFilter();
    if (status !== 'ALL') {
      query.status = status;
    }
    return query;
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (
      typeof error === 'object' &&
      error !== null &&
      'error' in error &&
      typeof error.error === 'object' &&
      error.error !== null &&
      'message' in error.error &&
      typeof error.error.message === 'string'
    ) {
      return error.error.message;
    }
    return fallback;
  }

  private formatPayload(raw: string): string {
    if (!raw) {
      return '';
    }
    try {
      const parsed: unknown = JSON.parse(raw);
      return JSON.stringify(parsed, null, 2) ?? raw;
    } catch {
      return raw;
    }
  }
}
