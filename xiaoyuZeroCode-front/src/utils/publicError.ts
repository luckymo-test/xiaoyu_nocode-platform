const PUBLIC_MESSAGES = [
  '生成任务已取消',
  '生成步骤超过上限，请简化需求或提高推理强度后重试',
  '生成过程未正常结束，请重试',
  '生成依赖准备失败，请稍后重试',
  '生成项目构建未通过，请重试',
  '生成环境校验失败，请重试',
  '生成响应超时，请稍后重试',
  '模型服务暂时不可用，请稍后重试',
  '生成任务执行失败，请稍后重试',
]

/**
 * The backend is expected to return a public message. This client-side guard
 * still strips accidental exception text, file paths, commands and log paths.
 */
export function userFacingGenerationError(message?: string): string {
  const value = (message ?? '').trim()
  const known = PUBLIC_MESSAGES.find((item) => value.includes(item))
  if (known) return known

  const normalized = value.toLowerCase()
  if (normalized.includes('cancel') || normalized.includes('取消')) {
    return '生成任务已取消'
  }
  if (normalized.includes('max_steps') || normalized.includes('最大工具轮数')) {
    return '生成步骤超过上限，请简化需求或提高推理强度后重试'
  }
  if (normalized.includes('package-lock') || normalized.includes('npm')
    || normalized.includes('dependency') || normalized.includes('依赖')) {
    return '生成依赖准备失败，请稍后重试'
  }
  if (normalized.includes('build') || normalized.includes('compile')
    || normalized.includes('vite') || normalized.includes('typescript')
    || normalized.includes('构建') || normalized.includes('编译')) {
    return '生成项目构建未通过，请重试'
  }
  if (normalized.includes('timeout') || normalized.includes('timed out')
    || normalized.includes('超时')) {
    return '生成响应超时，请稍后重试'
  }
  if (normalized.includes('model') || normalized.includes('deepseek')
    || normalized.includes('token') || normalized.includes('stream')
    || normalized.includes('429') || normalized.includes('502')
    || normalized.includes('503') || normalized.includes('504')) {
    return '模型服务暂时不可用，请稍后重试'
  }
  return '生成任务执行失败，请稍后重试'
}
