import request from '@/request'

export interface AppFileNode {
  path: string
  size: number
  sha256: string
}

export interface AppFileContent {
  path: string
  content: string
  sha256: string
}

export function listAppFiles(appId: string) {
  return request<{ code: number; data: AppFileNode[]; message: string }>(
    `/v1/apps/${appId}/files/tree`,
    { method: 'GET' },
  )
}

export function getAppFile(appId: string, path: string) {
  return request<{ code: number; data: AppFileContent; message: string }>(
    `/v1/apps/${appId}/files/content`,
    { method: 'GET', params: { path } },
  )
}

export function saveAppFile(
  appId: string,
  path: string,
  content: string,
  expectedSha256: string,
) {
  return request<{ code: number; data: AppFileContent; message: string }>(
    `/v1/apps/${appId}/files/content`,
    { method: 'PUT', data: { path, content, expectedSha256 } },
  )
}
