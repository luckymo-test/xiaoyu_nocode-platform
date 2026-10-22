import request from '@/request'

interface BaseResponse<T> {
  code: number
  data: T
  message: string
}

export interface AppArtifact {
  id: string
  artifactType: string
  uri: string
  sha256?: string
  sizeBytes?: number
  mimeType?: string
}

export interface AppRevision {
  id: string
  appId: string
  revisionNo: number
  jobId?: string
  baseRevisionId?: string
  status: string
  codeGenType: string
  validationStatus?: string
  createdBy: string
  publishedAt?: string
  createTime: string
  artifacts?: AppArtifact[]
}

export interface RevisionDiffFile {
  path: string
  changeType: 'ADDED' | 'MODIFIED' | 'DELETED'
  fromSha256?: string
  toSha256?: string
}

export interface RevisionDiff {
  fromRevisionId: string
  toRevisionId: string
  files: RevisionDiffFile[]
}

export function listAppRevisions(appId: string) {
  return request<BaseResponse<AppRevision[]>>(`/v1/apps/${appId}/revisions`, {
    method: 'GET',
  })
}

export function getAppRevision(appId: string, revisionId: string) {
  return request<BaseResponse<AppRevision>>(`/v1/apps/${appId}/revisions/${revisionId}`, {
    method: 'GET',
  })
}

export function compareAppRevisions(appId: string, from: string, to: string) {
  return request<BaseResponse<RevisionDiff>>(`/v1/apps/${appId}/revisions/compare`, {
    method: 'GET',
    params: { from, to },
  })
}

export function publishAppRevision(appId: string, revisionId: string) {
  return request<BaseResponse<AppRevision>>(
    `/v1/apps/${appId}/revisions/${revisionId}/publish`,
    { method: 'POST' },
  )
}

export function rollbackAppRevision(appId: string, revisionId: string) {
  return request<BaseResponse<AppRevision>>(
    `/v1/apps/${appId}/revisions/${revisionId}/rollback`,
    { method: 'POST' },
  )
}
