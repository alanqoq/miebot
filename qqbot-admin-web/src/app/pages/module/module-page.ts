import { Component, DestroyRef, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { switchMap } from 'rxjs';
import { ModuleCatalogService, ModuleWebContribution } from '../../core/module-catalog.service';

@Component({
  selector: 'app-module-page',
  templateUrl: './module-page.html',
  styleUrl: './module-page.scss'
})
export class ModulePage {
  private readonly route = inject(ActivatedRoute);
  private readonly modules = inject(ModuleCatalogService);
  private readonly destroyRef = inject(DestroyRef);
  private renderedElement: HTMLElement | null = null;

  @ViewChild('moduleHost', { static: true }) private moduleHost!: ElementRef<HTMLElement>;

  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  constructor() {
    this.route.paramMap
      .pipe(
        switchMap((parameters) =>
          this.modules.load().pipe(
            // Keep route identity next to the catalog result.
            switchMap(async () => ({
              moduleId: parameters.get('moduleId') ?? '',
              contributionId: parameters.get('contributionId') ?? ''
            }))
          )
        ),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: ({ moduleId, contributionId }) => {
          const contribution = this.modules.findContribution(moduleId, contributionId);
          if (!contribution || contribution.componentKey) {
            this.markFailed();
            return;
          }
          void this.render(contribution);
        },
        error: () => this.markFailed()
      });
  }

  private async render(contribution: ModuleWebContribution): Promise<void> {
    try {
      await this.modules.loadWebComponent(contribution);
      if (!contribution.customElement) throw new Error('Custom element is missing');
      this.renderedElement?.remove();
      const element = document.createElement(contribution.customElement);
      element.setAttribute('module-id', contribution.moduleId);
      element.setAttribute('contribution-id', contribution.id);
      element.style.display = 'block';
      element.style.minWidth = '0';
      this.moduleHost.nativeElement.appendChild(element);
      this.renderedElement = element;
      this.failed.set(false);
      this.loading.set(false);
    } catch {
      this.markFailed();
    }
  }

  private markFailed(): void {
    this.loading.set(false);
    this.failed.set(true);
  }
}
