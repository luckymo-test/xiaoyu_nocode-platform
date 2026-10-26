import request from '@/request'

export function issuePreviewToken(appId: string) {
  return request<{
    code: number
    data: { url: string; expiresAt: string }
    message: string
  }>(`/v1/apps/${appId}/preview-token`, { method: 'GET' })
}
