import { Router } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installModuleBridge } from './module-bridge';

describe('module bridge', () => {
  afterEach(() => {
    delete window.qqbot;
    vi.unstubAllGlobals();
  });

  it('provides same-origin requests with CSRF and shell navigation', async () => {
    const navigateByUrl = vi.fn().mockResolvedValue(true);
    const navigate = vi.fn().mockResolvedValue(true);
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=test-token; path=/';
    installModuleBridge({ navigateByUrl, navigate, url: '/modules/test/page' } as unknown as Router);

    await window.qqbot!.request('/api/test', { method: 'POST' });
    const request = fetchMock.mock.calls[0];
    const init = request[1] as RequestInit;
    expect(request[0]).toBe('/api/test');
    expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('test-token');
    expect(init.credentials).toBe('same-origin');

    await window.qqbot!.navigate('/modules/test/page');
    expect(navigateByUrl).toHaveBeenCalledWith('/modules/test/page');
    await expect(window.qqbot!.navigate('relative')).rejects.toThrow('must start with /');
  });

  it('publishes notifications and exposes theme tokens', () => {
    installModuleBridge({} as Router);
    document.documentElement.style.setProperty('--surface', '#fff');
    const listener = vi.fn();
    window.addEventListener('qqbot:notification', listener, { once: true });

    window.qqbot!.notify({ level: 'success', message: 'Saved' });

    expect(listener).toHaveBeenCalledOnce();
    expect((listener.mock.calls[0][0] as CustomEvent).detail).toEqual({
      level: 'success',
      message: 'Saved'
    });
    expect(window.qqbot!.themeToken('--surface')).toBe('#fff');
  });
});
