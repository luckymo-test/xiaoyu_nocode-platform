import request from '@/request'

export interface ProjectTemplate {
  id: string
  displayName: string
  description: string
  initialPrompt: string
  visualTemplate: string
}

export interface CreatedProject {
  appId: string
  templateId: string
  initialPrompt: string
}

export function listProjectTemplates() {
  return request<{ code: number; data: ProjectTemplate[]; message: string }>(
    '/v1/project-templates',
    { method: 'GET' },
  )
}

export function createProjectFromTemplate(templateId: string, appName?: string) {
  return request<{ code: number; data: CreatedProject; message: string }>(
    '/v1/apps/from-template',
    { method: 'POST', data: { templateId, appName } },
  )
}

export function clearSampleData(appId: string) {
  return request(`/v1/apps/${appId}/sample-data`, { method: 'DELETE' })
}
