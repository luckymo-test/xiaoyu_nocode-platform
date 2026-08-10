import { createSseConnection, type SseCallbacks, type SseConnection } from '@/utils/sse'

/**
 * 流式生成单文件 HTML 项目
 * 对应后端 GET /ai/chat/stream
 *
 * @param appId 应用 id（雪花 ID 超 JS 安全整数，需按字符串传递）
 */
export function streamGenerateHtmlProject(
  appId: number | string,
  message: string | undefined,
  callbacks: SseCallbacks,
): SseConnection {
  return createSseConnection(
    '/ai/chat/stream',
    {
      appId,
      message: message ?? '你好，请简单介绍一下你自己',
    },
    callbacks,
  )
}

/**
 * 流式生成多文件项目
 * 对应后端 GET /ai/chat/multi/stream
 *
 * @param appId 应用 id（雪花 ID 超 JS 安全整数，需按字符串传递）
 */
export function streamGenerateMultiFileProject(
  appId: number | string,
  message: string | undefined,
  callbacks: SseCallbacks,
): SseConnection {
  return createSseConnection(
    '/ai/chat/multi/stream',
    {
      appId,
      message: message ?? '你好，请简单介绍一下你自己',
    },
    callbacks,
  )
}
