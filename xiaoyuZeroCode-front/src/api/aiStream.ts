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

/**
 * 流式生成 Vue3 工程（agent 逐文件生成）
 * 对应后端 GET /ai/chat/vue/stream
 * agent_progress 事件携带 "path:size" 文件进度；done 携带最终 VO（含 files）
 */
export function streamGenerateVueProject(
  appId: number | string,
  message: string | undefined,
  callbacks: SseCallbacks,
): SseConnection {
  return createSseConnection(
    '/ai/chat/vue/stream',
    {
      appId,
      message: message ?? '你好，请简单介绍一下你自己',
    },
    callbacks,
  )
}

/**
 * 可视化修改：给定点选元素上下文 + 修改意图，让 AI 精确定位源码改动。
 * 对应后端 GET /ai/modify/stream（SSE，agent_progress/reasoning/done/error）。
 * 仅应用创建者可调用；html / multi_file 支持。
 */
export function streamModifyProject(
  appId: number | string,
  elementContext: string,
  instruction: string,
  callbacks: SseCallbacks,
): SseConnection {
  return createSseConnection(
    '/ai/modify/stream',
    {
      appId,
      elementContext,
      instruction,
    },
    callbacks,
  )
}
