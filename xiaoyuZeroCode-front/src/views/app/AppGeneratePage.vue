<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined, UserOutlined, RobotOutlined, DoubleRightOutlined, DoubleLeftOutlined } from '@ant-design/icons-vue'
import { getAppVoById, getAppCode, deployApp, unDeployApp } from '@/api/appController'
import { chat, chatMulti, stopGenerate } from '@/api/aiChatController'
import { listChatHistory } from '@/api/chatHistoryController'
import {
  streamGenerateHtmlProject,
  streamGenerateMultiFileProject,
} from '@/api/aiStream'
import type { SseCallbacks, SseConnection } from '@/utils/sse'
import { useUserStore } from '@/stores/user'
import MarkdownView from '@/components/MarkdownView.vue'
import { generateStatusText } from '@/utils/appStatus'

interface ChatMessage {
  role: 'user' | 'ai'
  content: string
  status?: 'streaming' | 'done' | 'error'
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const appId = computed(() => route.params.id as string)
const app = ref<API.AppVO | null>(null)
const loading = ref(true)

const messages = ref<ChatMessage[]>([])
const input = ref('')
const generating = ref(false)

const previewDoc = ref('')
const previewLoading = ref(false)
const deploying = ref(false)

// 预览模式：code=代码预览（默认），web=网页预览（代码流式完成后才可切换）
type PreviewMode = 'code' | 'web'
const previewMode = ref<PreviewMode>('code')
// 代码预览展示的文本：流式时实时累积的原始 JSON，完成后为完整 JSON
const streamRawJson = ref('')
// 本次生成是否已完成（完成后才允许切到网页预览）
const generationDone = ref(false)
// 格式化后的完整代码（供代码预览展示）
const formattedCode = ref('')
// 右侧预览面板是否展开（伸缩框）
const previewExpanded = ref(true)

// 对话历史每页条数（与后端默认一致）
const HISTORY_PAGE_SIZE = 10
// 游标：当前已加载消息中最老一条的 createTime / id，用于向前加载更早记录
const historyCursor = ref<{ beforeCreateTime?: string; beforeId?: number }>({})
const hasMoreHistory = ref(false)
const historyLoading = ref(false)

const msgListRef = ref<HTMLElement | null>(null)
let connection: SseConnection | null = null

const isMultiFile = computed(() => app.value?.codeGenType === 'multi_file')

// 是否流式生成：创建时通过 query 传入（?stream=true/false），默认流式
const useStream = computed(() => route.query.stream !== 'false')

const loadApp = async () => {
  loading.value = true
  try {
    const res = await getAppVoById({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      app.value = res.data.data
      const isOwner = app.value.userId === userStore.loginUser?.id
      const records = await loadChatHistory()
      // 网站展示：至少 2 条对话记录（即至少完成过一轮对话）才展示对应的网站
      if (records.length >= 2) {
        await tryLoadWebsite()
      }
      // 自动发送初始消息：仅自己的应用且没有对话历史时，将 initPrompt 作为第一条消息触发对话
      if (isOwner && records.length === 0 && app.value?.initPrompt) {
        sendMessage(app.value.initPrompt, true)
      }
    } else {
      message.error(res.data.message || '加载应用失败')
    }
  } catch {
    message.error('加载应用失败')
  } finally {
    loading.value = false
  }
}

// 加载最新一页对话历史（后端按创建时间正序返回），作为已加载列表的初始数据
const loadChatHistory = async (): Promise<API.ChatHistory[]> => {
  try {
    const res = await listChatHistory({
      appId: appId.value as unknown as number,
      pageSize: HISTORY_PAGE_SIZE,
    })
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data
      messages.value = records.map(toChatMessage)
      updateCursor(records)
      hasMoreHistory.value = records.length === HISTORY_PAGE_SIZE
      return records
    }
    // 非应用创建者无权查看历史等场景，按无历史处理
    hasMoreHistory.value = false
  } catch {
    hasMoreHistory.value = false
  }
  return []
}

// 加载更早一页历史消息，拼接到已加载列表之前
const loadMoreHistory = async () => {
  if (historyLoading.value || !hasMoreHistory.value) return
  historyLoading.value = true
  try {
    const res = await listChatHistory({
      appId: appId.value as unknown as number,
      pageSize: HISTORY_PAGE_SIZE,
      beforeCreateTime: historyCursor.value.beforeCreateTime,
      beforeId: historyCursor.value.beforeId,
    })
    if (res.data.code === 0 && res.data.data) {
      const older = res.data.data
      messages.value = [...older.map(toChatMessage), ...messages.value]
      updateCursor(older)
      hasMoreHistory.value = older.length === HISTORY_PAGE_SIZE
    } else {
      hasMoreHistory.value = false
    }
  } finally {
    historyLoading.value = false
  }
}

// 游标取已加载消息中最老一条（列表升序，最后一条最老）
const updateCursor = (records: API.ChatHistory[]) => {
  const oldest = records[records.length - 1]
  if (oldest) {
    historyCursor.value = {
      beforeCreateTime: oldest.createTime,
      beforeId: oldest.id,
    }
  }
}

const toChatMessage = (item: API.ChatHistory): ChatMessage => ({
  role: item.messageType === 'user' ? 'user' : 'ai',
  content: item.message ?? '',
  status: item.messageType === 'error' ? 'error' : 'done',
})

// 加载已生成的代码并展示对应的网站
const tryLoadWebsite = async () => {
  try {
    const res = await getAppCode({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      const result = res.data.data
      previewDoc.value = buildPreviewDoc(result)
      formattedCode.value = buildFormattedCode(result)
      // 已有生成结果：允许切换预览，并直接展示网站
      generationDone.value = true
      previewMode.value = 'web'
    }
  } catch {
    // 读取失败不阻塞页面
  }
}

const sendMessage = async (text?: string, auto = false) => {
  const content = (text ?? input.value).trim()
  if (!content) {
    message.warning('请输入消息')
    return
  }
  if (generating.value) {
    message.warning('正在生成中，请稍候')
    return
  }
  if (!auto) {
    input.value = ''
  }

  messages.value.push({ role: 'user', content })
  // 必须用 reactive 创建，否则 aiMsg 是普通对象，后续 aiMsg.content = 赋值
  // 不会触发 Vue proxy 的 set，streaming 期间 UI 不会实时刷新
  const aiMsg = reactive<ChatMessage>({ role: 'ai', content: '', status: 'streaming' })
  messages.value.push(aiMsg)
  generating.value = true
  previewLoading.value = true
  previewDoc.value = ''
  // 重置本次生成的预览状态：默认回到代码预览，生成完成前不可切网页预览
  previewMode.value = 'code'
  generationDone.value = false
  streamRawJson.value = ''
  formattedCode.value = ''

  if (useStream.value) {
    // 后端 token 逐字流出。description 阶段用 reveal 逐字打字机；
    // 代码块出现后停止 reveal，改由 onToken 整段刷新，保证代码流式可见。
    let rawJson = ''
    let targetText = ''
    let flushTimer: ReturnType<typeof setInterval> | null = null
    let codeStarted = false
    let lastFlush = 0
    const reveal = () => {
      const target = targetText
      if (target === '' || codeStarted) return
      const current = aiMsg.content
      if (target.length > current.length) {
        aiMsg.content = target.slice(0, current.length + 1)
      }
    }
    flushTimer = setInterval(reveal, 20)
    const stopReveal = () => {
      if (flushTimer) {
        clearInterval(flushTimer)
        flushTimer = null
      }
    }
    const finishReveal = (finalText: string) => {
      stopReveal()
      aiMsg.content = finalText
    }
    const callbacks: SseCallbacks = {
      onToken: (token) => {
        rawJson += token
        // 同步累积的原始 JSON 到响应式 ref，右侧代码预览实时显示
        streamRawJson.value = rawJson
        const now = Date.now()
        if (now - lastFlush < 30) return
        lastFlush = now
        const md = buildStreamingMarkdown(rawJson)
        // 代码块已到达：停止打字机，整段刷新显示代码
        if (md.includes('```')) {
          if (!codeStarted) {
            codeStarted = true
            stopReveal()
          }
          aiMsg.content = md
        } else {
          targetText = md
        }
      },
      onDone: (result) => {
        finishReveal(buildMarkdownContent(result))
        aiMsg.status = 'done'
        previewDoc.value = buildPreviewDoc(result)
        formattedCode.value = buildFormattedCode(result)
        previewLoading.value = false
        generating.value = false
        generationDone.value = true
      },
      onError: (errorMsg) => {
        finishReveal(friendlyError(errorMsg))
        aiMsg.status = 'error'
        previewLoading.value = false
        generating.value = false
        generationDone.value = true
      },
      onClose: () => {
        stopReveal()
        generating.value = false
      },
    }
    if (isMultiFile.value) {
      connection = streamGenerateMultiFileProject(appId.value, content, callbacks)
    } else {
      connection = streamGenerateHtmlProject(appId.value, content, callbacks)
    }
  } else {
    // 非流式：一次性返回完整结果（后端直接返回 AiCodeGenResultVO，无 BaseResponse 包装）
    try {
      const res = isMultiFile.value
        ? await chatMulti({ appId: appId.value as unknown as number, message: content })
        : await chat({ appId: appId.value as unknown as number, message: content })
      const result = res.data
      if (result) {
        aiMsg.content = buildMarkdownContent(result)
        aiMsg.status = 'done'
        previewDoc.value = buildPreviewDoc(result)
        formattedCode.value = buildFormattedCode(result)
      } else {
        aiMsg.content = '生成失败：返回结果为空'
        aiMsg.status = 'error'
      }
    } catch {
      aiMsg.content = '生成失败：网络异常'
      aiMsg.status = 'error'
    } finally {
      previewLoading.value = false
      generating.value = false
      generationDone.value = true
    }
  }
}

// 将后端错误信息映射为友好文案（网络断连等常见情况）
const friendlyError = (errorMsg: string): string => {
  if (/connection reset|connection closed|connection refused|eof|timeout|premature close/i.test(errorMsg)) {
    return '生成失败：网络连接不稳定，请重试'
  }
  if (/jsonmappingexception|parse/i.test(errorMsg)) {
    return '生成失败：AI 输出不完整，请重试或简化需求'
  }
  // 其他错误截断展示，避免刷屏技术堆栈
  return '生成失败：' + errorMsg.slice(0, 80)
}

// 从流式累积的 JSON 片段中增量提取 description 字段，实现打字机效果。
// description 值未结束（缺右引号）时也能取到已输出的部分。
const extractDescription = (raw: string): string | null => {
  const full = raw.match(/"description"\s*:\s*"((?:[^"\\]|\\.)*)"/)
  if (full) return full[1]
  // 部分：值正在流式中，取到当前为止
  const partial = raw.match(/"description"\s*:\s*"((?:[^"\\]|\\.)*)$/)
  if (partial) return partial[1]
  return null
}

// 线性扫描从流式 JSON 中提取字符串字段（如 htmlCode/cssCode/jsCode）。
// 字段未出现返回 null；已闭合返回真实字符串；流式中未闭合返回已到达部分（手动反转义）。
const extractFieldLinear = (raw: string, field: string): string | null => {
  const marker = `"${field}":"`
  const idx = raw.indexOf(marker)
  if (idx === -1) return null
  const start = idx + marker.length
  let i = start
  let closed = false
  while (i < raw.length) {
    const c = raw[i]
    if (c === '\\') {
      i += 2
      continue
    }
    if (c === '"') {
      closed = true
      break
    }
    i++
  }
  const segment = raw.slice(start, i)
  if (segment === '' || segment === 'null') return null
  if (closed) {
    try {
      return JSON.parse('"' + segment + '"')
    } catch {
      return segment
    }
  }
  // 未闭合：把已到达的转义序列转成真实字符
  return segment
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\r/g, '\r')
    .replace(/\\\\/g, '\\')
}

// 从流式累积的 JSON 片段拼出当前可见的 Markdown（description + 已到达的代码块）
const buildStreamingMarkdown = (raw: string): string => {
  const parts: string[] = []
  const desc = extractDescription(raw)
  if (desc) parts.push(desc)
  const html = extractFieldLinear(raw, 'htmlCode')
  if (html) parts.push('#### index.html\n```html\n' + html + '\n```')
  if (isMultiFile.value) {
    const css = extractFieldLinear(raw, 'cssCode')
    if (css) parts.push('#### css/styles.css\n```css\n' + css + '\n```')
    const js = extractFieldLinear(raw, 'jsCode')
    if (js) parts.push('#### js/app.js\n```javascript\n' + js + '\n```')
  }
  return parts.join('\n\n')
}

const buildPreviewDoc = (result: API.AiCodeGenResultVO): string => {
  if (isMultiFile.value) {
    return [
      '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">',
      '<style>' + (result.cssCode ?? '') + '</style>',
      '</head><body>' + (result.htmlCode ?? ''),
      '<' + 'script>' + (result.jsCode ?? '') + '</' + 'script>',
      '</body></html>',
    ].join('')
  }
  return result.htmlCode ?? ''
}

// 生成格式化的完整代码文本，供"代码预览"展示
const buildFormattedCode = (result: API.AiCodeGenResultVO): string => {
  const parts: string[] = []
  parts.push('// ===== description =====')
  parts.push(result.description || '')
  if (isMultiFile.value) {
    if (result.htmlCode) {
      parts.push('// ===== index.html =====')
      parts.push(result.htmlCode)
    }
    if (result.cssCode) {
      parts.push('// ===== css/styles.css =====')
      parts.push(result.cssCode)
    }
    if (result.jsCode) {
      parts.push('// ===== js/app.js =====')
      parts.push(result.jsCode)
    }
  } else {
    if (result.htmlCode) {
      parts.push('// ===== index.html =====')
      parts.push(result.htmlCode)
    }
  }
  return parts.join('\n\n')
}

// 将生成结果拼成 Markdown，供 AI 消息展示（含代码块，MarkdownView 高亮渲染）
const buildMarkdownContent = (result: API.AiCodeGenResultVO): string => {
  const parts: string[] = []
  if (result.description) {
    parts.push(result.description)
  }
  if (isMultiFile.value) {
    if (result.htmlCode) {
      parts.push('#### index.html\n```html\n' + result.htmlCode + '\n```')
    }
    if (result.cssCode) {
      parts.push('#### css/styles.css\n```css\n' + result.cssCode + '\n```')
    }
    if (result.jsCode) {
      parts.push('#### js/app.js\n```javascript\n' + result.jsCode + '\n```')
    }
  } else {
    if (result.htmlCode) {
      parts.push('#### index.html\n```html\n' + result.htmlCode + '\n```')
    }
  }
  return parts.join('\n\n')
}

const handleDeploy = async () => {
  if (!app.value?.id) return
  deploying.value = true
  try {
    if (app.value.deployKey) {
      // 已部署 → 下线
      const res = await unDeployApp({ id: app.value.id })
      if (res.data.code === 0) {
        message.success('已下线')
        await loadApp()
      } else {
        message.error(res.data.message || '下线失败')
      }
    } else {
      // 未部署 → 上线
      const res = await deployApp({ appId: app.value.id })
      if (res.data.code === 0 && res.data.data) {
        message.success('部署成功')
        await loadApp()
        window.open(res.data.data, '_blank')
      } else {
        message.error(res.data.message || '部署失败')
      }
    }
  } catch {
    message.error('操作失败')
  } finally {
    deploying.value = false
  }
}

// 停止生成：断开 SSE + 通知后端中断
const handleStop = async () => {
  connection?.close()
  connection = null
  generating.value = false
  previewLoading.value = false
  try {
    await stopGenerate({ appId: appId.value as unknown as number })
    message.info('已停止生成')
  } catch {
    // 后端已停止或连接断开，忽略
  }
}

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    sendMessage()
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (msgListRef.value) {
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight
  }
  // Markdown 是 v-html 异步渲染，等渲染完成后再次滚动到底
  setTimeout(() => {
    if (msgListRef.value) {
      msgListRef.value.scrollTop = msgListRef.value.scrollHeight
    }
  }, 50)
}
watch(
  () => messages.value[messages.value.length - 1]?.content,
  scrollToBottom,
)

onMounted(() => {
  loadApp()
})

onUnmounted(() => {
  connection?.close()
})
</script>

<template>
  <div class="gen-page">
    <div class="gen-header">
      <a-space size="middle">
        <a-button type="text" @click="router.back()">
          <template #icon><ArrowLeftOutlined /></template>
        </a-button>
        <span class="app-name">{{ app?.appName || '应用生成' }}</span>
        <a-tag v-if="app && isMultiFile" color="purple">多文件</a-tag>
        <a-tag v-else-if="app" color="blue">单文件</a-tag>
        <a-tag
          v-if="app?.generateStatus"
          :color="generateStatusText(app.generateStatus).color"
        >
          {{ generateStatusText(app.generateStatus).text }}
        </a-tag>
      </a-space>
      <a-space>
        <a-button
          v-if="generating"
          danger
          @click="handleStop"
        >
          停止生成
        </a-button>
        <a-button
          type="primary"
          :loading="deploying"
          @click="handleDeploy"
        >
          {{ app?.deployKey ? '下线' : '部署' }}
        </a-button>
      </a-space>
    </div>

    <div class="gen-body">
      <div class="chat-panel">
        <div ref="msgListRef" class="msg-list">
          <a-spin v-if="loading" class="msg-loading" />
          <div v-if="hasMoreHistory" class="load-more-bar">
            <a-button type="link" size="small" :loading="historyLoading" @click="loadMoreHistory">
              加载更多
            </a-button>
          </div>
          <div
            v-for="(msg, index) in messages"
            :key="index"
            class="msg-row"
            :class="msg.role"
          >
            <a-avatar
              class="msg-avatar"
              :class="msg.role"
              :src="msg.role === 'user' ? userStore.loginUser?.userAvatar : undefined"
            >
              <template #icon>
                <UserOutlined v-if="msg.role === 'user'" />
                <RobotOutlined v-else />
              </template>
            </a-avatar>
            <div class="msg-bubble" :class="{ 'msg-streaming': msg.status === 'streaming' }">
              <MarkdownView v-if="msg.role === 'ai'" :content="msg.content" />
              <span v-else>{{ msg.content }}</span>
            </div>
          </div>
        </div>
        <div class="input-bar">
          <a-textarea
            v-model:value="input"
            placeholder="描述你的需求，回车发送（Shift+Enter 换行）"
            :auto-size="{ minRows: 2, maxRows: 6 }"
            :disabled="generating"
            @keydown="handleKeydown"
          />
          <a-button
            type="primary"
            :loading="generating"
            :disabled="generating"
            @click="sendMessage()"
          >
            {{ generating ? '生成中' : '发送' }}
          </a-button>
        </div>
      </div>

      <div class="panel-toggle" @click="previewExpanded = !previewExpanded">
        <DoubleRightOutlined v-if="!previewExpanded" />
        <DoubleLeftOutlined v-else />
      </div>

      <div v-show="previewExpanded" class="preview-panel">
        <div class="preview-header">
          <a-select
            v-model:value="previewMode"
            style="width: 130px"
            :options="[
              { label: '代码预览', value: 'code' },
              { label: '网页预览', value: 'web' },
            ]"
            :disabled="!generationDone"
          />
          <a-spin v-if="previewLoading" size="small" />
        </div>
        <div class="preview-content">
          <template v-if="previewMode === 'code'">
            <pre v-if="streamRawJson || formattedCode" class="code-frame">{{
              formattedCode || streamRawJson
            }}</pre>
            <div v-else class="preview-empty">
              <a-empty
                :description="previewLoading ? '正在生成代码，请稍候...' : '代码生成后在此展示'"
              />
            </div>
          </template>
          <template v-else>
            <template v-if="previewDoc">
              <iframe
                :srcdoc="previewDoc"
                class="preview-frame"
                sandbox="allow-scripts allow-same-origin allow-modals allow-forms allow-popups"
              />
            </template>
            <div v-else class="preview-empty">
              <a-empty
                :description="previewLoading ? '正在生成，请稍候...' : '生成完成后将在此展示网页效果'"
              />
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gen-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px - 50px - 48px);
  max-width: 1200px;
  margin: 0 auto;
}

.gen-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #fff;
  border-radius: 8px 8px 0 0;
  flex-shrink: 0;
}

.app-name {
  font-size: 16px;
  font-weight: 600;
}

.gen-body {
  display: flex;
  flex: 1;
  min-height: 0;
  background: #fff;
  border-top: 1px solid #f0f0f0;
  border-radius: 0 0 8px 8px;
  overflow: hidden;
}

.chat-panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.panel-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  cursor: pointer;
  color: #999;
  background: #fafafa;
  border-left: 1px solid #f0f0f0;
  border-right: 1px solid #f0f0f0;
  flex-shrink: 0;
  user-select: none;
  transition: background 0.2s;
}

.panel-toggle:hover {
  background: #f0f5ff;
  color: #1677ff;
}

.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg-loading {
  margin: 24px auto;
}

.load-more-bar {
  display: flex;
  justify-content: center;
  flex-shrink: 0;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.msg-row.user {
  /* 微信风格：头像在最右，气泡在头像左侧，整体靠右。
     row-reverse 主轴从右到左，flex-start 指向视觉右侧 */
  justify-content: flex-start;
  flex-direction: row-reverse;
}

.msg-row.ai {
  justify-content: flex-start;
}

.msg-avatar {
  flex-shrink: 0;
  margin-top: 2px;
}

.msg-bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-row.user .msg-bubble {
  background: #1677ff;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.msg-row.ai .msg-bubble {
  background: #f5f5f5;
  color: #333;
  border-bottom-left-radius: 4px;
}

.msg-bubble.msg-streaming {
  background: #f5f5f5;
}

.stream-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #666;
  white-space: pre-wrap;
  word-break: break-all;
}

.stream-text::after {
  content: '▌';
  color: #1677ff;
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

.input-bar {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #f0f0f0;
  align-items: flex-end;
  flex-shrink: 0;
}

.input-bar :deep(.ant-input) {
  resize: none;
}

.preview-panel {
  display: flex;
  flex-direction: column;
  flex: 1.2;
  min-width: 0;
}

.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  font-weight: 600;
  flex-shrink: 0;
}

.preview-content {
  flex: 1;
  min-height: 0;
  background: #fafafa;
}

.preview-frame {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.code-frame {
  height: 100%;
  margin: 0;
  padding: 16px;
  overflow: auto;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.preview-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 768px) {
  .gen-page {
    height: auto;
  }

  .gen-body {
    flex-direction: column;
  }

  .chat-panel {
    border-right: none;
    border-bottom: 1px solid #f0f0f0;
    min-height: 400px;
  }

  .preview-panel {
    min-height: 400px;
  }
}
</style>
