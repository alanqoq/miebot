import { Router } from '@angular/router';

export type ModuleNotificationLevel = 'info' | 'success' | 'warning' | 'error';

export interface ModuleNotification {
  level: ModuleNotificationLevel;
  message: string;
}

export interface QqBotModuleBridge {
  readonly version: 1;
  request(input: string, init?: RequestInit): Promise<Response>;
  navigate(path: string): Promise<boolean>;
  notify(notification: ModuleNotification): void;
  confirm(message: string): Promise<boolean>;
  themeToken(name: string): string;
}

declare global {
  interface Window {
    qqbot?: QqBotModuleBridge;
  }
}

export function installModuleBridge(router: Router): void {
  const bridge: QqBotModuleBridge = {
    version: 1,
    async request(input: string, init: RequestInit = {}): Promise<Response> {
      if (!input.startsWith('/')) {
        throw new Error('Module API requests must use a same-origin absolute path');
      }
      const headers = new Headers(init.headers);
      headers.set('Accept', headers.get('Accept') ?? 'application/json');
      const method = (init.method ?? 'GET').toUpperCase();
      if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
        const csrfToken = cookie('XSRF-TOKEN');
        if (csrfToken) {
          headers.set('X-XSRF-TOKEN', csrfToken);
        }
      }
      const response = await fetch(input, {
        ...init,
        headers,
        credentials: 'same-origin'
      });
      if (response.status === 401) {
        await router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      }
      return response;
    },
    navigate(path: string): Promise<boolean> {
      if (!path.startsWith('/')) {
        return Promise.reject(new Error('Navigation path must start with /'));
      }
      return router.navigateByUrl(path);
    },
    notify(notification: ModuleNotification): void {
      window.dispatchEvent(new CustomEvent<ModuleNotification>(
        'qqbot:notification',
        { detail: notification }
      ));
    },
    confirm(message: string): Promise<boolean> {
      return Promise.resolve(window.confirm(message));
    },
    themeToken(name: string): string {
      if (!name.startsWith('--')) {
        throw new Error('Theme token must be a CSS custom property');
      }
      return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }
  };
  window.qqbot = Object.freeze(bridge);
}

function cookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`;
  const value = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return value ? decodeURIComponent(value.slice(prefix.length)) : null;
}
