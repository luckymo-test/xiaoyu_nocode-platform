import { message as staticMessage } from 'ant-design-vue'

/**
 * 全局反馈（toast）适配层。
 *
 * 背景：antd 的 `message` 静态调用不会继承 `ConfigProvider` 的主题（颜色/圆角仍然是默认值），
 * 只有通过 `App.useApp()` 拿到的实例才跟随主题。但 `request.ts` / `router` / `stores`
 * 这些非组件模块没法调用 `useApp()`，所以这里做一层持有者：
 *
 * - `BasicLayout.vue` 挂载时把 `App.useApp()` 的 message 实例注册进来；
 * - 其余所有地方统一 `import { appMessage as message } from '@/utils/feedback'`；
 * - 注册发生之前（比如应用启动最早期的请求）自动降级到 antd 静态 message，提示不会丢。
 */

type MessageLike = {
  success: (content: string) => unknown
  error: (content: string) => unknown
  warning: (content: string) => unknown
  info: (content: string) => unknown
  loading: (content: string) => unknown
}

let messageApi: MessageLike | null = null

export const registerMessage = (api: MessageLike) => {
  messageApi = api
}

const call = (method: keyof MessageLike, content: string) => {
  const target = messageApi ?? staticMessage
  const fn = target[method] as ((content: string) => unknown) | undefined
  return fn ? fn.call(target, content) : undefined
}

export const appMessage: MessageLike = {
  success: (content: string) => call('success', content),
  error: (content: string) => call('error', content),
  warning: (content: string) => call('warning', content),
  info: (content: string) => call('info', content),
  loading: (content: string) => call('loading', content),
}
