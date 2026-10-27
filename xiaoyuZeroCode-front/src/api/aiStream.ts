import { createSseConnection, type SseCallbacks, type SseConnection } from '@/utils/sse'
import { BASE_URL } from '@/request'
import { CSRF_HEADER, ensureCsrfToken, getCsrfToken } from '@/utils/csrf'

interface GenerationJobVO {
  jobId: string
  status: string
  eventsUrl: string
}

interface BaseResponse<T> {
  code: number
  data: T
  message: string
}

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `job-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/**
 * 创建持久化 Job 后订阅只读事件。
 * EventSource 自动重连会携带 Last-Event-ID，服务端从下一个 seq 重放。
 */
function streamGenerationJob(
  appId: number | string,
  message: string | undefined,
  codeGenType: 'html' | 'multi_file' | 'vue',
  callbacks: SseCallbacks,
  reasoningLevel: string,
  stackProfileId = 'auto',
  projectTemplateId?: string,
): SseConnection {
  let closed = false
  let eventSource: EventSource | null = null

  const close = () => {
    if (closed) return
    closed = true
    eventSource?.close()
    callbacks.onClose?.()
  }

  void (async () => {
    try {
      await ensureCsrfToken(BASE_URL)
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Idempotency-Key': newIdempotencyKey(),
      }
      const csrfToken = getCsrfToken()
      if (csrfToken) {
        headers[CSRF_HEADER] = csrfToken
      }
      const response = await fetch(`${BASE_URL}/v1/apps/${appId}/generation-jobs`, {
        method: 'POST',
        credentials: 'include',
        headers,
        body: JSON.stringify({
          message: message ?? '你好，请简单介绍一下你自己',
          reasoningLevel,
          codeGenType,
          stackProfileId,
          templateHint: 'auto',
          projectTemplateId,
          skills: [],
          requestType: 'generate',
          workflowVersion: 2,
        }),
      })
      const body = (await response.json()) as BaseResponse<GenerationJobVO>
      if (!response.ok || body.code !== 0 || !body.data) {
        callbacks.onError?.(body.message || `创建生成任务失败（HTTP ${response.status}）`)
        close()
        return
      }
      if (closed) return

      const job = body.data
      callbacks.onJobCreated?.(job)
      eventSource = new EventSource(`${BASE_URL}${job.eventsUrl}`, { withCredentials: true })
      eventSource.onopen = () => callbacks.onOpen?.()

      eventSource.addEventListener('progress', (event) => {
        const value = parsePayload(event)
        callbacks.onAgentProgress?.(
          typeof value === 'string' ? value : JSON.stringify(value),
        )
      })
      eventSource.addEventListener('completed', (event) => {
        const result = parsePayload(event) as API.AiCodeGenResultVO
        callbacks.onWorkflowEvent?.('completed', result)
        callbacks.onDone?.(result)
        close()
      })
      eventSource.addEventListener('failed', (event) => {
        const payload = parsePayload(event) as { message?: string } | string
        callbacks.onWorkflowEvent?.('failed', payload)
        callbacks.onError?.(
          typeof payload === 'string' ? payload : payload?.message || '生成失败',
        )
        close()
      })
      eventSource.addEventListener('cancelled', (event) => {
        const payload = parsePayload(event) as { message?: string } | string
        callbacks.onWorkflowEvent?.('cancelled', payload)
        callbacks.onError?.(
          typeof payload === 'string' ? payload : payload?.message || '用户取消生成',
        )
        close()
      })
      for (const eventType of [
        'plan_ready',
        'confirmation_required',
        'plan_confirmed',
        'coder_started',
        'step_completed',
        'pause_requested',
        'paused',
        'resumed',
      ]) {
        eventSource.addEventListener(eventType, (event) => {
          callbacks.onWorkflowEvent?.(eventType, parsePayload(event))
        })
      }
      eventSource.onerror = () => {
        // 只读事件接口没有副作用，浏览器原生重连比重新创建 Job 更安全。
        if (eventSource?.readyState === EventSource.CLOSED && !closed) {
          callbacks.onConnectionLost?.()
          close()
        }
      }
    } catch (error) {
      const messageText = error instanceof Error ? error.message : '创建生成任务失败'
      callbacks.onError?.(messageText)
      close()
    }
  })()

  return {
    close,
    get readyState() {
      return eventSource?.readyState ?? EventSource.CONNECTING
    },
  }
}

function parsePayload(event: Event): unknown {
  const data = (event as MessageEvent).data
  try {
    return JSON.parse(data)
  } catch {
    return data
  }
}

export function subscribeJobEvents(
  eventsUrl: string,
  callbacks: SseCallbacks,
): SseConnection {
  let closed = false
  const source = new EventSource(`${BASE_URL}${eventsUrl}`, {
    withCredentials: true,
  })
  const close = () => {
    if (closed) return
    closed = true
    source.close()
    callbacks.onClose?.()
  }
  source.onopen = () => callbacks.onOpen?.()
  source.addEventListener('deployed', (event) => {
    const payload = parsePayload(event) as { url?: string }
    if (payload.url) callbacks.onDeployed?.(payload.url)
  })
  source.addEventListener('completed', () => {
    callbacks.onWorkflowEvent?.('completed', null)
    close()
  })
  source.addEventListener('failed', (event) => {
    const payload = parsePayload(event) as { message?: string } | string
    callbacks.onWorkflowEvent?.('failed', payload)
    callbacks.onError?.(
      typeof payload === 'string' ? payload : payload?.message || '任务失败',
    )
    close()
  })
  source.addEventListener('cancelled', (event) => {
    const payload = parsePayload(event) as { message?: string } | string
    callbacks.onWorkflowEvent?.('cancelled', payload)
    callbacks.onError?.(
      typeof payload === 'string' ? payload : payload?.message || '任务已取消',
    )
    close()
  })
  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED && !closed) {
      callbacks.onConnectionLost?.()
      close()
    }
  }
  return {
    close,
    get readyState() {
      return source.readyState
    },
  }
}

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
  reasoningLevel = 'standard',
  projectTemplateId?: string,
): SseConnection {
  return streamGenerationJob(appId, message, 'html', callbacks, reasoningLevel,
    'auto', projectTemplateId)
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
  reasoningLevel = 'standard',
  projectTemplateId?: string,
): SseConnection {
  return streamGenerationJob(appId, message, 'multi_file', callbacks, reasoningLevel,
    'auto', projectTemplateId)
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
  reasoningLevel = 'standard',
  stackProfileId = 'auto',
  projectTemplateId?: string,
): SseConnection {
  return streamGenerationJob(appId, message, 'vue', callbacks, reasoningLevel,
    stackProfileId, projectTemplateId)
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
  reasoningLevel = 'standard',
): SseConnection {
  return createSseConnection(
    '/ai/modify/stream',
    {
      appId,
      elementContext,
      instruction,
      reasoningLevel,
    },
    callbacks,
  )
}

export function streamDeployment(
  appId: number | string,
  callbacks: SseCallbacks,
): SseConnection {
  let source: EventSource | null = null
  let closed = false
  const close = () => {
    if (closed) return
    closed = true
    source?.close()
    callbacks.onClose?.()
  }
  void (async () => {
    try {
      await ensureCsrfToken(BASE_URL)
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Idempotency-Key': newIdempotencyKey(),
      }
      const csrf = getCsrfToken()
      if (csrf) headers[CSRF_HEADER] = csrf
      const response = await fetch(`${BASE_URL}/v1/apps/${appId}/deployments`, {
        method: 'POST',
        credentials: 'include',
        headers,
      })
      const body = (await response.json()) as BaseResponse<GenerationJobVO>
      if (!response.ok || body.code !== 0 || !body.data) {
        callbacks.onError?.(body.message || '创建部署任务失败')
        close()
        return
      }
      callbacks.onJobCreated?.(body.data)
      source = new EventSource(`${BASE_URL}${body.data.eventsUrl}`, {
        withCredentials: true,
      })
      source.addEventListener('deployed', (event) => {
        const payload = parsePayload(event) as { url?: string }
        if (payload.url) callbacks.onDeployed?.(payload.url)
      })
      source.addEventListener('completed', () => close())
      source.addEventListener('failed', (event) => {
        const payload = parsePayload(event) as { message?: string }
        callbacks.onError?.(payload.message || '部署失败')
        close()
      })
      source.onerror = () => {
        if (source?.readyState === EventSource.CLOSED && !closed) {
          callbacks.onConnectionLost?.()
          close()
        }
      }
    } catch (error) {
      callbacks.onError?.(error instanceof Error ? error.message : '部署失败')
      close()
    }
  })()
  return {
    close,
    get readyState() {
      return source?.readyState ?? EventSource.CONNECTING
    },
  }
}
