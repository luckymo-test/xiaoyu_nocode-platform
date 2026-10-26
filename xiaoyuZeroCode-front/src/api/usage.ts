import request from '@/request'

export interface UsageSummary {
  from: string
  to: string
  generationCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimatedCostMicros: number
  storageBytes: number
}

export function getMyUsage() {
  return request<{ code: number; data: UsageSummary; message: string }>(
    '/v1/users/me/usage',
    { method: 'GET' },
  )
}
