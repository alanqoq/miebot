import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { ModuleCatalogService } from '../../core/module-catalog.service';

@Component({
  selector: 'app-module-landing-page',
  template: `
    <div class="module-landing" role="status">
      {{ failed() ? '没有可用的模块页面' : '正在打开模块页面' }}
    </div>
  `,
  styles: `
    .module-landing {
      padding: 24px 0;
      color: var(--muted);
    }
  `
})
export class ModuleLandingPage {
  private readonly modules = inject(ModuleCatalogService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly failed = signal(false);

  constructor() {
    this.modules.load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          const target = this.modules.navigation()[0]?.route;
          if (target) {
            void this.router.navigateByUrl(target, { replaceUrl: true });
          } else {
            this.failed.set(true);
          }
        },
        error: () => this.failed.set(true)
      });
  }
}
