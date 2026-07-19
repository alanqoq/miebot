import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type PluginArtifactStatus = 'DISCOVERED' | 'INVALID' | 'UNSUPPORTED' | string;

export interface PluginArtifact {
  id: string;
  name: string;
  version: string;
  apiCompatibility: string;
  fileName: string;
  sizeBytes: number;
  modifiedAt: string;
  sha256: string;
  status: PluginArtifactStatus;
  error: string | null;
  loaded: boolean;
  bindingCount: number;
  enabledBindingCount: number;
}

export interface PluginInventory {
  items: PluginArtifact[];
  directory: string;
  directoryExists: boolean;
  runtimeAvailable: boolean;
  scanError: string | null;
  scannedAt: string;
}

export interface PluginBinding {
  id: string;
  pluginId: string;
  botId: string;
  configJson: string;
  enabled: boolean;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePluginBindingRequest {
  pluginId: string;
  botId: string;
  configJson: string;
  enabled: boolean;
}

export interface UpdatePluginBindingRequest {
  expectedRevision: number;
  configJson: string;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class PluginApiService {
  private readonly http = inject(HttpClient);

  list(query?: string): Observable<PluginInventory> {
    let params = new HttpParams();
    if (query?.trim()) {
      params = params.set('query', query.trim());
    }
    return this.http.get<PluginInventory>('/api/plugins', { params });
  }

  reload(query?: string): Observable<PluginInventory> {
    let params = new HttpParams();
    if (query?.trim()) params = params.set('query', query.trim());
    return this.http.post<PluginInventory>('/api/plugins/reload', null, { params });
  }

  listBindings(pluginId?: string, botId?: string): Observable<PluginBinding[]> {
    let params = new HttpParams();
    if (pluginId) params = params.set('pluginId', pluginId);
    if (botId) params = params.set('botId', botId);
    return this.http.get<PluginBinding[]>('/api/plugin-bindings', { params });
  }

  createBinding(request: CreatePluginBindingRequest): Observable<PluginBinding> {
    return this.http.post<PluginBinding>('/api/plugin-bindings', request);
  }

  updateBinding(id: string, request: UpdatePluginBindingRequest): Observable<PluginBinding> {
    return this.http.put<PluginBinding>(`/api/plugin-bindings/${encodeURIComponent(id)}`, request);
  }

  deleteBinding(id: string): Observable<void> {
    return this.http.delete<void>(`/api/plugin-bindings/${encodeURIComponent(id)}`);
  }
}
