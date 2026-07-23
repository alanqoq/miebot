import {
  ApplicationRef,
  Type,
  createComponent
} from '@angular/core';
import {
  HttpErrorResponse,
  HttpInterceptorFn,
  provideHttpClient,
  withInterceptors,
  withXsrfConfiguration
} from '@angular/common/http';
import { createApplication } from '@angular/platform-browser';
import { catchError, throwError } from 'rxjs';
import type { QqBotModuleBridge } from '../app/core/module-bridge';

interface ModuleElementDefinition {
  name: string;
  component: Type<unknown>;
}

const moduleUnauthorizedInterceptor: HttpInterceptorFn = (request, next) =>
  next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        void window.qqbot?.navigate('/login');
      }
      return throwError(() => error);
    })
  );

export async function registerModuleElements(
  definitions: ModuleElementDefinition[]
): Promise<void> {
  const application = await createApplication({
    providers: [
      provideHttpClient(
        withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
        withInterceptors([moduleUnauthorizedInterceptor])
      )
    ]
  });

  for (const definition of definitions) {
    if (customElements.get(definition.name)) {
      continue;
    }
    customElements.define(
      definition.name,
      moduleElement(application, definition.component)
    );
  }
}

function moduleElement(
  application: ApplicationRef,
  component: Type<unknown>
): CustomElementConstructor {
  return class extends HTMLElement {
    private componentRef: ReturnType<typeof createComponent> | null = null;

    connectedCallback(): void {
      if (this.componentRef) {
        return;
      }
      this.componentRef = createComponent(component, {
        environmentInjector: application.injector,
        hostElement: this
      });
      application.attachView(this.componentRef.hostView);
      this.componentRef.changeDetectorRef.detectChanges();
    }

    disconnectedCallback(): void {
      if (!this.componentRef) {
        return;
      }
      application.detachView(this.componentRef.hostView);
      this.componentRef.destroy();
      this.componentRef = null;
    }
  };
}
