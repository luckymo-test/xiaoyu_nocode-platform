import request from '@/request'

export interface AgentPlan {
  id: string
  jobId: string
  appId: string
  planVersion: number
  status: string
  requirementJson: string
  architectureJson: string
  uiJson: string
  executionPlanJson?: string
  planHash: string
  confirmedAt?: string
  confirmedBy?: string
}

export interface AgentStep {
  id: string
  stepKey: string
  agentType: string
  title: string
  status: string
  dependenciesJson?: string
  outputJson?: string
  attempt: number
  errorMessage?: string
}

export interface GenerationJob {
  jobId: string
  status: string
  currentStage?: string
  workflowVersion?: number
  confirmedPlanHash?: string
  errorType?: string
  errorMessage?: string
  createTime?: string
  updateTime?: string
}

export interface AgentToolCall {
  id: string
  toolName: string
  status: string
  filePath?: string
  durationMs?: number
  resultSummary?: string
  errorMessage?: string
  afterSha256?: string
}

export function getGenerationJob(jobId: string) {
  return request<{ code: number; data: GenerationJob; message: string }>(
    `/v1/generation-jobs/${jobId}`,
    { method: 'GET' },
  )
}

export function getLatestGenerationJob(appId: number | string) {
  return request<{ code: number; data: GenerationJob | null; message: string }>(
    `/v1/apps/${appId}/generation-jobs/latest`,
    { method: 'GET' },
  )
}

export function getGenerationPlan(jobId: string) {
  return request<{ code: number; data: AgentPlan; message: string }>(
    `/v1/generation-jobs/${jobId}/plan`,
    { method: 'GET' },
  )
}

export function getGenerationSteps(jobId: string) {
  return request<{ code: number; data: AgentStep[]; message: string }>(
    `/v1/generation-jobs/${jobId}/steps`,
    { method: 'GET' },
  )
}

export function getGenerationTools(jobId: string) {
  return request<{ code: number; data: AgentToolCall[]; message: string }>(
    `/v1/generation-jobs/${jobId}/tools`,
    { method: 'GET' },
  )
}

export function confirmGenerationPlan(jobId: string, planHash: string) {
  return request(`/v1/generation-jobs/${jobId}/confirm`, {
    method: 'POST',
    data: { planHash },
  })
}

export function reviseGenerationPlan(jobId: string, planHash: string, comment: string) {
  return request(`/v1/generation-jobs/${jobId}/plan/revise`, {
    method: 'POST',
    data: { planHash, comment },
  })
}

export function pauseGeneration(jobId: string) {
  return request(`/v1/generation-jobs/${jobId}/pause`, { method: 'POST' })
}

export function resumeGeneration(jobId: string) {
  return request(`/v1/generation-jobs/${jobId}/resume`, { method: 'POST' })
}

export function cancelGeneration(jobId: string) {
  return request(`/v1/generation-jobs/${jobId}/cancel`, { method: 'POST' })
}

export function retryGeneration(jobId: string) {
  return request(`/v1/generation-jobs/${jobId}/retry`, { method: 'POST' })
}

export function retryGenerationStep(jobId: string, stepKey: string) {
  return request(
    `/v1/generation-jobs/${jobId}/steps/${encodeURIComponent(stepKey)}/retry`,
    { method: 'POST' },
  )
}
