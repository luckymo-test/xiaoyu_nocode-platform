import { BASE_URL } from '@/request'

export interface SseCallbacks {
  /** 每收到一个 token 增量时触发 */
  onToken?: (token: string) => void
  /** 生成完成，返回最终结果 VO */
  onDone?: (result: API.AiCodeGenResultVO) => void
  /** 后端主动报错 */
  onError?: (message: string) => void
  /** 连接建立成功 */
  onOpen?: () => void
  /** 连接关闭（正常完成或出错后均会触发） */
  onClose?: () => void
  /** 连接异常断开；后端任务可能仍在继续，调用方应轮询恢复结果 */
  onConnectionLost?: () => void
  /** Vue 工程逐文件生成进度（data 为 "path:size"） */
  onAgentProgress?: (data: string) => void
  /** 部署/构建进度（data 为文本标记，如 START_BUILD / BUILD_FAILED|...） */
  onDeployProgress?: (data: string) => void
}

export interface SseConnection {
  close: () => void
  readonly readyState: number
}

/**
 * 发起 SSE 流式请求
 * @param path 后端接口路径，如 /ai/chat/stream
 * @param params 查询参数
 * @param callbacks 事件回调
 */
export function createSseConnection(
  path: string,
  params: Record<string, string | number | undefined>,
  callbacks: SseCallbacks,
): SseConnection {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      query.set(key, String(value))
    }
  }
  const url = `${BASE_URL}${path}?${query.toString()}`
  // 必须 withCredentials 携带 session cookie，否则接口会 40100
  const es = new EventSource(url, { withCredentials: true })
  let closed = false

  const close = () => {
    if (!closed) {
      closed = true
      es.close()
      callbacks.onClose?.()
    }
  }

  // 生成接口是 GET 且有副作用，浏览器自动重连会重复触发一次生成。
  // 因此网络异常时主动关闭连接，由调用方通过应用状态恢复最终结果。
  const connectionLost = () => {
    if (!closed) {
      closed = true
      es.close()
      callbacks.onConnectionLost?.()
    }
  }

  es.onopen = () => {
    callbacks.onOpen?.()
  }

  es.addEventListener('token', (event) => {
    callbacks.onToken?.(event.data)
  })

  es.addEventListener('agent_progress', (event) => {
    callbacks.onAgentProgress?.(event.data)
  })

  es.addEventListener('deploy_progress', (event) => {
    callbacks.onDeployProgress?.(event.data)
  })

  es.addEventListener('stream_lost', () => {
    connectionLost()
  })

  es.addEventListener('done', (event) => {
    try {
      const result = JSON.parse(event.data) as API.AiCodeGenResultVO
      callbacks.onDone?.(result)
    } catch (e) {
      callbacks.onError?.(`解析生成结果失败: ${event.data}`)
    } finally {
      close()
    }
  })

  es.addEventListener('error', (event) => {
    // SSE 网络层错误没有 data 字段；后端发的 error 事件 data 为错误信息
    const data = (event as MessageEvent).data
    if (data) {
      callbacks.onError?.(data)
      close()
    } else {
      connectionLost()
    }
  })

  return {
    close,
    get readyState() {
      return es.readyState
    },
  }
}
