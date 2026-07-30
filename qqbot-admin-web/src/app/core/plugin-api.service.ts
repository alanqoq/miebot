import { HttpClient, HttpEvent, HttpHeaders, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type PluginArtifactStatus = 'DISCOVERED' | 'INVALID' | 'UNSUPPORTED' | string;
export type PluginConfigurationFormat = 'JSON' | 'YAML';

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
  defaultConfigJson: string | null;
  defaultConfigContent?: string | null;
  configFormat?: PluginConfigurationFormat | null;
  configFileName?: string | null;
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
  enabled: boolean;
  revision: number;
  createdAt: string;
  updatedAt: string;
  runtimeState: 'ACTIVE' | 'PAUSED' | 'QUARANTINED' | string;
  runtimeError: string | null;
}

export interface CreatePluginBindingRequest {
  pluginId: string;
  botId: string;
  configContent?: string;
  configJson?: string;
  enabled: boolean;
}

export interface UpdatePluginBindingRequest {
  expectedRevision: number;
  enabled: boolean;
}

export interface PluginFileEntry {
  name: string;
  path: string;
  directory: boolean;
  sizeBytes: number;
  modifiedAt: string;
  contentType: string | null;
}

export interface PluginFileListing {
  path: string;
  entries: PluginFileEntry[];
}

export interface PluginTextFile {
  path: string;
  content: string;
  sha256: string;
  modifiedAt: string;
}

export interface SavePluginTextFileRequest {
  path: string;
  content: string;
  expectedSha256: string;
}

export interface CreatePluginFileEntryRequest {
  path: string;
  directory: boolean;
}

export interface PluginUploadResponse {
  operation: string;
  artifact: PluginArtifact;
  previousVersion: string | null;
  previousSha256: string | null;
}

export const MAX_PLUGIN_UPLOAD_BYTES = 64 * 1024 * 1024;

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

  upload(file: File): Observable<HttpEvent<PluginUploadResponse>> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<PluginUploadResponse>('/api/plugins/upload', form, {
      headers: new HttpHeaders({ 'X-Plugin-Upload-Confirm': 'trusted-jar' }),
      observe: 'events',
      reportProgress: true,
    });
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

  resetBinding(id: string): Observable<PluginBinding> {
    return this.http.post<PluginBinding>(`/api/plugin-bindings/${encodeURIComponent(id)}/reset`, null);
  }

  listBindingFiles(id: string, path = ''): Observable<PluginFileListing> {
    const params = new HttpParams().set('path', path);
    return this.http.get<PluginFileListing>(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files`,
      { params },
    );
  }

  getBindingFileContent(id: string, path: string): Observable<PluginTextFile> {
    const params = new HttpParams().set('path', path);
    return this.http.get<PluginTextFile>(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files/content`,
      { params },
    );
  }

  saveBindingFileContent(
    id: string,
    request: SavePluginTextFileRequest,
  ): Observable<PluginTextFile> {
    return this.http.put<PluginTextFile>(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files/content`,
      request,
    );
  }

  createBindingFileEntry(
    id: string,
    request: CreatePluginFileEntryRequest,
  ): Observable<PluginFileEntry> {
    return this.http.post<PluginFileEntry>(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files/entries`,
      request,
    );
  }

  uploadBindingFile(
    id: string,
    directory: string,
    file: File,
    overwrite = false,
    expectedSha256?: string,
  ): Observable<PluginFileEntry> {
    const form = new FormData();
    form.append('file', file, file.name);
    let params = new HttpParams()
      .set('directory', directory)
      .set('overwrite', overwrite);
    if (expectedSha256) params = params.set('expectedSha256', expectedSha256);
    return this.http.post<PluginFileEntry>(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files/upload`,
      form,
      { params },
    );
  }

  downloadBindingFile(id: string, path: string): Observable<Blob> {
    const params = new HttpParams().set('path', path);
    return this.http.get(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files/download`,
      { params, responseType: 'blob' },
    );
  }

  deleteBindingFile(id: string, path: string): Observable<void> {
    const params = new HttpParams().set('path', path);
    return this.http.delete<void>(
      `/api/plugin-bindings/${encodeURIComponent(id)}/files`,
      { params },
    );
  }
}
