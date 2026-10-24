import myAxios from '@/request'

export interface RuntimeProfile {
  id: string
  version: string
  displayName: string
  compatibilityType: 'html' | 'multi_file' | 'vue'
  capabilities: string[]
  available: boolean
  unavailableReason?: string
}

export async function listRuntimeProfiles() {
  const res = await myAxios.get('/v1/runtime-profiles')
  return res.data as {
    code: number
    data: RuntimeProfile[]
    message: string
  }
}
