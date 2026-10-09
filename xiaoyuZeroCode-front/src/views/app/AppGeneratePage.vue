<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined, UserOutlined, RobotOutlined, DoubleRightOutlined, DoubleLeftOutlined, LinkOutlined, FileTextOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import { getAppVoById, getAppCode, getVuePreview, deployApp, unDeployApp, downloadCode } from '@/api/appController'
import { chat, chatMulti, stopGenerate, undoAiModify } from '@/api/aiChatController'
import {
  listManualEdits,
  applyManualEdit,
  undoManualEdit,
  clearManualEdits,
} from '@/api/manualEdit'
import type { ManualEditItem } from '@/api/manualEdit'
import { listChatHistory } from '@/api/chatHistoryController'
import {
  streamGenerateHtmlProject,
  streamGenerateMultiFileProject,
  streamGenerateVueProject,
  streamModifyProject,
} from '@/api/aiStream'
import { createSseConnection } from '@/utils/sse'
import { getRenderFixStatus } from '@/api/renderFix'
import type { SseCallbacks, SseConnection } from '@/utils/sse'
import { useUserStore } from '@/stores/user'
import MarkdownView from '@/components/MarkdownView.vue'
import { generateStatusText } from '@/utils/appStatus'

interface CodeFile {
  path: string
  content: string
}

interface ChatMessage {
  role: 'user' | 'ai'
  content: string
  status?: 'streaming' | 'done' | 'error'
  /** 该消息生成的文件清单（气泡里只显示文件名，点击在右侧预览查看完整代码） */
  files?: CodeFile[]
  /** 模型思考过程（流式增量拼接，可折叠展示） */
  reasoning?: string
  /** token 元信息（本次生成消耗；历史消息无此字段） */
  usage?: { promptTokens?: number; completionTokens?: number; totalTokens?: number }
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

// 生成中的动态进度文案（如"正在生成 index.html…"、"正在搜索图片…"），用于让用户明确生成在推进
const genProgress = ref('')

const previewDoc = ref('')
const previewLoading = ref(false)
const deploying = ref(false)

// 预览模式：code=代码预览（默认），web=网页预览（代码流式完成后才可切换）
type PreviewMode = 'code' | 'web'
const previewMode = ref<PreviewMode>('code')
// 本次生成是否已完成（完成后才允许切到网页预览）
const generationDone = ref(false)
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
const isVue = computed(() => app.value?.codeGenType === 'vue')

// 预览面板：统一文件集 + 文件树（生成中实时累加；点击对话里的文件清单可切换；html/multi_file/vue 共用）
const previewFiles = ref<CodeFile[]>([])
const vueProgress = ref('')
// vue 构建产物预览加载中（首次构建较慢）
const vuePreviewLoading = ref(false)
// 文件树中当前选中的文件（用于代码预览分文件查看）
const selectedFilePath = ref('')
const selectedFileContent = computed(
  () => previewFiles.value.find((f) => f.path === selectedFilePath.value)?.content ?? '',
)
// 把选中的文件内容包成 markdown 代码块（按扩展名选语言），供 MarkdownView 语法高亮渲染
const selectedFileMarkdown = computed(() => {
  const content = selectedFileContent.value
  if (!content) return ''
  const lang = langOfFile(selectedFilePath.value)
  return '```' + lang + '\n' + content + '\n```'
})
// 按文件扩展名推断代码语言（highlight.js 语言名）
function langOfFile(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  switch (ext) {
    case 'vue': return 'html'
    case 'js': case 'mjs': case 'cjs': return 'javascript'
    case 'ts': return 'typescript'
    case 'css': return 'css'
    case 'html': case 'htm': return 'html'
    case 'json': return 'json'
    case 'md': case 'mdx': return 'markdown'
    default: return ''
  }
}
const selectFile = (path: string) => {
  selectedFilePath.value = path
}

// 加载 vue 工程构建产物的自包含预览 HTML（后端 dist 不存在时自动构建）
const loadVuePreview = async (): Promise<boolean> => {
  try {
    vuePreviewLoading.value = true
    const res = await getVuePreview({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      previewDoc.value = res.data.data
      generationDone.value = true
      previewMode.value = 'web'
      return true
    }
  } catch {
    // 构建失败或网络异常
  } finally {
    vuePreviewLoading.value = false
  }
  return false
}

// ============ 可视化编辑（html / multi_file 网页预览支持） ============
const previewFrameRef = ref<HTMLIFrameElement | null>(null)
const editMode = ref(false)
const editTarget = ref<{ selector: string; tag: string; text: string; outerHtml: string } | null>(null)
const editInstruction = ref('')
const editStatus = ref('')
const modifying = ref(false)
// 撤销快照：修改前整份源码文件快照（供一键还原）
const undoSnapshot = ref<CodeFile[]>([])
let editorDetach: (() => void) | null = null

// —— 手动样式编辑：页签 / 元素句柄 / 属性值 ——
const editTab = ref<'ai' | 'style'>('style')
const editTargetEl = ref<HTMLElement | null>(null)
let pendingReselect = '' // 手动样式刷新后，重新选中该选择器以继续迭代
const styleTouched = ref<string[]>([]) // 被用户改过的 css 属性名
const styleVal = reactive<Record<string, string>>({}) // 每个受控属性的当前值（字符串）
const manualEdits = ref<ManualEditItem[]>([])
const styleBusy = ref(false)
// 文字内容（手动编辑 text 类型）
const styleText = ref('')

const toggleVisualEdit = () => {
  if (editMode.value) {
    exitVisualEdit()
  } else {
    enterVisualEdit()
  }
}

const enterVisualEdit = () => {
  editMode.value = true
  editStatus.value = '点击网页中的元素进行选择'
  armPreviewEditor()
  loadManualEdits()
}

const exitVisualEdit = () => {
  editMode.value = false
  disarmPreviewEditor()
  editTarget.value = null
  editTargetEl.value = null
  pendingReselect = ''
  styleTouched.value = []
  editInstruction.value = ''
  editStatus.value = ''
  manualEdits.value = []
}

// 为预览 iframe 注入高亮/点击选择（同源 srcdoc，可访问 contentDocument）
const armPreviewEditor = () => {
  disarmPreviewEditor()
  const frame = previewFrameRef.value
  const doc = frame?.contentDocument
  if (!frame || !doc) return
  let hovered: Element | null = null
  const clearHover = () => {
    if (hovered) {
      ;(hovered as HTMLElement).style.outline = ''
      hovered = null
    }
  }
  const onMouseOver = (e: Event) => {
    if (!editMode.value) return
    const t = e.target as Element
    if (t === doc.documentElement || t === doc.body) {
      clearHover()
      return
    }
    clearHover()
    hovered = t
    ;(t as HTMLElement).style.outline = '1px dashed #1677ff'
    e.stopPropagation()
  }
  const onClick = (e: Event) => {
    if (!editMode.value) return
    e.preventDefault()
    e.stopPropagation()
    const t = e.target as Element
    if (t === doc.documentElement || t === doc.body) return
    selectEditTarget(t)
  }
  doc.addEventListener('mouseover', onMouseOver, true)
  doc.addEventListener('click', onClick, true)
  editorDetach = () => {
    doc.removeEventListener('mouseover', onMouseOver, true)
    doc.removeEventListener('click', onClick, true)
    clearHover()
  }
}

const disarmPreviewEditor = () => {
  if (editorDetach) {
    editorDetach()
    editorDetach = null
  }
}

// srcdoc 内容变化会整体 reload，需要在 load 后重新武装监听
const onPreviewFrameLoad = () => {
  if (editMode.value) {
    armPreviewEditor()
  }
  // 手动样式落地后重新选中同一元素，便于继续微调
  if (pendingReselect) {
    const sel = pendingReselect
    pendingReselect = ''
    const doc = previewFrameRef.value?.contentDocument
    if (doc) {
      try {
        const el = doc.querySelector(sel)
        if (el) {
          selectEditTarget(el)
        }
      } catch {
        // 选择器在新文档中失效，忽略
      }
    }
  }
}

// 生成元素的 CSS 选择器路径（优先 id，其次 class，同名加 nth-of-type）
const cssPathOf = (el: Element): string => {
  const parts: string[] = []
  let cur: Element | null = el
  while (cur && cur !== cur.ownerDocument?.documentElement && parts.length < 8) {
    const tag = cur.tagName.toLowerCase()
    if (cur.id) {
      parts.unshift(`${tag}#${cur.id}`)
      break
    }
    let seg = tag
    const firstClasses = Array.from(cur.classList).slice(0, 2)
    if (firstClasses.length > 0) {
      seg += '.' + firstClasses.join('.')
    }
    const parent = cur.parentElement
    if (parent) {
      const siblings = Array.from(parent.children).filter((c) => c.tagName === cur!.tagName)
      if (siblings.length > 1) {
        seg += `:nth-of-type(${siblings.indexOf(cur) + 1})`
      }
    }
    parts.unshift(seg)
    cur = cur.parentElement
  }
  return parts.join(' > ')
}

const describeElement = (el: Element) => {
  const id = el.id ? `#${el.id}` : ''
  const cls = Array.from(el.classList).join('.')
  return {
    selector: cssPathOf(el),
    tag: `${el.tagName.toLowerCase()}${id}${cls ? '.' + cls : ''}`,
    text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 200),
    outerHtml: (el as HTMLElement).outerHTML.slice(0, 600),
  }
}

const selectEditTarget = (el: Element) => {
  editTarget.value = describeElement(el)
  editTargetEl.value = el as HTMLElement
  editInstruction.value = ''
  pendingReselect = ''
  initStylePanel()
  editTab.value = 'style'
  editStatus.value = '已选中元素，可在「样式」或「AI」页签修改'
}

// 拼装给后端的元素上下文文本
const buildEditContext = (): string => {
  const t = editTarget.value
  if (!t) return ''
  return `选择器: ${t.selector}\n标签: ${t.tag}\n文本: ${t.text || '(空)'}\n结构片段: ${t.outerHtml || '(空)'}`
}

// 应用修改：走 /ai/modify/stream
const applyModify = async () => {
  const inst = editInstruction.value.trim()
  if (!inst) {
    message.warning('请描述想怎么改')
    return
  }
  if (!app.value?.id) return
  // 修改前快照，供撤销
  undoSnapshot.value = previewFiles.value.map((f) => ({ ...f }))
  modifying.value = true
  editStatus.value = 'AI 正在分析并修改…'
  const callbacks: SseCallbacks = {
    onAgentProgress: (data) => {
      try {
        const p = JSON.parse(data)
        const path: string = p.path || ''
        const content: string = p.content || ''
        if (path) {
          editStatus.value = `正在修改 ${path}…`
          upsertFile(previewFiles.value, { path, content })
        }
      } catch {
        // 忽略非关键事件
      }
    },
    onReasoning: () => {
      editStatus.value = 'AI 正在思考定位…'
    },
    onDone: (result) => {
      const files = filesFromResult(result)
      previewFiles.value = files
      if (files.length > 0 && !files.some((f) => f.path === selectedFilePath.value)) {
        selectedFilePath.value = files[0].path
      }
      previewDoc.value = buildPreviewDoc(result)
      generationDone.value = true
      previewMode.value = 'web'
      modifying.value = false
      editTarget.value = null
      editInstruction.value = ''
      editStatus.value = ''
      message.success('修改成功，预览已刷新')
    },
    onError: (m) => {
      modifying.value = false
      editStatus.value = ''
      message.error(m || '修改失败，请重试或换一种描述')
    },
    onClose: () => {
      modifying.value = false
    },
  }
  connection = streamModifyProject(appId.value, buildEditContext(), inst, callbacks)
}

const cancelEditTarget = () => {
  editTarget.value = null
  editTargetEl.value = null
  pendingReselect = ''
  styleTouched.value = []
  editInstruction.value = ''
  editStatus.value = editMode.value ? '点击网页中的元素进行选择' : ''
}

const handleEditKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    applyModify()
  }
}

// 撤销上次可视化修改：把修改前快照写回源码并刷新预览
const undoLastModify = async () => {
  if (!undoSnapshot.value.length || !app.value?.id) {
    message.info('暂无可撤销的修改')
    return
  }
  if (modifying.value) return
  const body: Record<string, string> = {}
  for (const f of undoSnapshot.value) {
    if (f.path) {
      body[f.path] = f.content
    }
  }
  modifying.value = true
  editStatus.value = '正在撤销…'
  try {
    const res = await undoAiModify(body, { appId: appId.value as unknown as number })
    const result = res.data?.data
    if (res.data?.code === 0 && result) {
      const files = filesFromResult(result)
      previewFiles.value = files
      if (files.length && !files.some((f) => f.path === selectedFilePath.value)) {
        selectedFilePath.value = files[0].path
      }
      previewDoc.value = buildPreviewDoc(result)
      generationDone.value = true
      previewMode.value = 'web'
      undoSnapshot.value = []
      editTarget.value = null
      editInstruction.value = ''
      message.success('已撤销本次修改')
    } else {
      message.error(res.data?.message || '撤销失败')
    }
  } catch (e: any) {
    message.error(e?.message || '撤销失败')
  } finally {
    modifying.value = false
    editStatus.value = ''
  }
}

// ============ 手动样式编辑（属性面板 → 覆盖规则落盘） ============
interface StyleField {
  css: string
  label: string
  kind: 'color' | 'px' | 'num' | 'weight' | 'align' | 'size'
}
const STYLE_FIELDS: StyleField[] = [
  { css: 'color', label: '文字颜色', kind: 'color' },
  { css: 'background-color', label: '背景色', kind: 'color' },
  { css: 'font-size', label: '字号(px)', kind: 'px' },
  { css: 'font-weight', label: '字重', kind: 'weight' },
  { css: 'text-align', label: '对齐', kind: 'align' },
  { css: 'margin-top', label: '上边距(px)', kind: 'px' },
  { css: 'padding', label: '内边距(px)', kind: 'px' },
  { css: 'border-radius', label: '圆角(px)', kind: 'px' },
  { css: 'line-height', label: '行高', kind: 'num' },
  { css: 'width', label: '宽度', kind: 'size' },
]
const PX_FIELDS = new Set(['font-size', 'margin-top', 'padding', 'border-radius'])

const curSelector = computed(() => editTarget.value?.selector ?? '')

// 计算值 → 控件显示值（px 字段取纯数字）
function styleInputValue(cssProp: string, computed: string): string {
  if (cssProp === 'font-weight' || cssProp === 'text-align' || cssProp === 'width') {
    return computed
  }
  if (cssProp === 'line-height') {
    return computed || ''
  }
  const n = parseFloat(computed)
  return Number.isFinite(n) ? String(Math.round(n)) : ''
}

// 控件值 → 最终 css 值（px 字段补单位）
function styleToCss(cssProp: string, raw: string): string {
  const v = (raw || '').trim()
  if (v === '') return ''
  if (PX_FIELDS.has(cssProp) || cssProp === 'width') {
    return /^-?\d+(\.\d+)?$/.test(v) ? v + 'px' : v
  }
  return v
}

// 读选中元素当前计算值，初始化控件
function initStylePanel() {
  const el = editTargetEl.value
  styleTouched.value = []
  for (const f of STYLE_FIELDS) {
    styleVal[f.css] = ''
  }
  styleText.value = el ? (el.textContent || '').trim() : ''
  if (!el) return
  const cs = getComputedStyle(el)
  for (const f of STYLE_FIELDS) {
    try {
      styleVal[f.css] = styleInputValue(f.css, cs.getPropertyValue(f.css) || '')
    } catch {
      styleVal[f.css] = ''
    }
  }
}

// 文字输入即时预览（替换整段文字内容）
const onTextInput = () => {
  const el = editTargetEl.value
  if (el && el.isConnected) {
    el.textContent = styleText.value
  }
}

// 属性变更：记录 touched + live 预览（改元素 inline，便于落盘前看效果）
const onStyleChange = (cssProp: string) => {
  if (!styleTouched.value.includes(cssProp)) {
    styleTouched.value.push(cssProp)
  }
  const el = editTargetEl.value
  if (el && el.isConnected) {
    const v = styleToCss(cssProp, styleVal[cssProp])
    if (v === '') {
      el.style.removeProperty(cssProp)
    } else {
      el.style.setProperty(cssProp, v)
    }
  }
}

const onStyleInput = (cssProp: string, e: Event) => {
  styleVal[cssProp] = (e.target as HTMLInputElement).value
  onStyleChange(cssProp)
}

const onStyleSelect = (cssProp: string, e: Event) => {
  styleVal[cssProp] = (e.target as HTMLSelectElement).value
  onStyleChange(cssProp)
}

// 计算出的颜色可能是 rgb(...)，转成 #rrggbb 供 color input 使用
function toHexColor(v: string): string {
  const s = String(v || '').trim()
  if (/^#[0-9a-fA-F]{6}$/.test(s)) {
    return s
  }
  const m = s.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/)
  if (m) {
    return (
      '#' +
      [1, 2, 3]
        .map((i) => Number(m[i]).toString(16).padStart(2, '0'))
        .join('')
    )
  }
  return '#000000'
}

// 组装覆盖规则（只含被改动且非空的属性）
function buildManualRule(): string {
  const props = styleTouched.value.filter((p) => styleToCss(p, styleVal[p]) !== '')
  if (props.length === 0) return ''
  const decl = props.map((p) => `  ${p}: ${styleToCss(p, styleVal[p])};`).join('\n')
  return `${sanitizeSelector(curSelector.value)} {\n${decl}\n}`
}

// 选择器净化：去掉会破坏 CSS 文件/块边界的字符
function sanitizeSelector(s: string): string {
  return (s || '').replace(/[{};'"\\]/g, '')
}

// 保存手动样式 → 刷新预览与历史
const applyManualStyle = async () => {
  const rule = buildManualRule()
  if (!rule) {
    message.warning('还没有改动，请先调整右侧属性')
    return
  }
  if (!app.value?.id) return
  styleBusy.value = true
  editStatus.value = '保存样式…'
  try {
    const res = await applyManualEdit(appId.value as unknown as number, {
      kind: 'style',
      selector: sanitizeSelector(curSelector.value),
      css: rule,
    })
    if (res.data?.code !== 0) {
      message.error(res.data?.message || '保存失败')
      return
    }
    message.success('已保存到源码')
    await refreshAfterManualEdit()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    styleBusy.value = false
    editStatus.value = ''
  }
}

// 保存文字内容：注入 index.html 的运行时覆盖脚本（带标记，可撤销）
const applyManualText = async () => {
  const text = styleText.value.trim()
  if (!text) {
    message.warning('请输入新的文字内容')
    return
  }
  if (!app.value?.id || !curSelector.value) return
  styleBusy.value = true
  editStatus.value = '保存文字…'
  try {
    const res = await applyManualEdit(appId.value as unknown as number, {
      kind: 'text',
      selector: sanitizeSelector(curSelector.value),
      text,
    })
    if (res.data?.code !== 0) {
      message.error(res.data?.message || '保存失败')
      return
    }
    message.success('已更新文字')
    await refreshAfterManualEdit()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    styleBusy.value = false
    editStatus.value = ''
  }
}

// 手动编辑落地后：重拉代码 → 刷新预览（iframe 重载并自动重选继续编辑）→ 刷新历史列表
const refreshAfterManualEdit = async () => {
  if (!app.value?.id) return
  try {
    const res = await getAppCode({ id: appId.value as unknown as number })
    const data = res.data?.data
    if (res.data?.code === 0 && data) {
      previewFiles.value = filesFromResult(data)
      previewDoc.value = buildPreviewDoc(data)
      previewMode.value = 'web'
      generationDone.value = true
      if (curSelector.value) {
        pendingReselect = sanitizeSelector(curSelector.value)
      }
    }
  } catch {
    // 忽略，历史列表仍可加载
  }
  await loadManualEdits()
}

const loadManualEdits = async () => {
  if (!app.value?.id) {
    manualEdits.value = []
    return
  }
  try {
    const res = await listManualEdits({ appId: appId.value as unknown as number })
    manualEdits.value = res.data?.data ?? []
  } catch {
    manualEdits.value = []
  }
}

const undoOneManual = async (id: string) => {
  if (!app.value?.id) return
  styleBusy.value = true
  try {
    const res = await undoManualEdit(appId.value as unknown as number, id)
    if (res.data?.code === 0) {
      message.success('已撤销该条')
      await refreshAfterManualEdit()
    } else {
      message.error(res.data?.message || '撤销失败')
    }
  } catch (e: any) {
    message.error(e?.message || '撤销失败')
  } finally {
    styleBusy.value = false
  }
}

const clearAllManual = async () => {
  if (!app.value?.id || manualEdits.value.length === 0) return
  styleBusy.value = true
  try {
    const res = await clearManualEdits(appId.value as unknown as number)
    if (res.data?.code === 0) {
      message.success('已清空手动修改')
      await refreshAfterManualEdit()
    } else {
      message.error(res.data?.message || '清空失败')
    }
  } catch (e: any) {
    message.error(e?.message || '清空失败')
  } finally {
    styleBusy.value = false
  }
}

// 部署进度（vue 流式部署状态文案）
const deployStatus = ref('')

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

const toChatMessage = (item: API.ChatHistory): ChatMessage => {
  const content = item.message ?? ''
  const isAi = item.messageType !== 'user'
  if (isAi) {
    // AI 消息存库是 description + 文件代码块，解析成"描述 + 文件清单"，气泡只显示清单
    const parsed = parseMessageContent(content)
    return {
      role: 'ai',
      content: item.messageType === 'error' ? content : parsed.description || '',
      status: item.messageType === 'error' ? 'error' : 'done',
      files: parsed.files,
    }
  }
  return { role: 'user', content, status: 'done' }
}

// 加载已生成的代码并展示对应的网站
const tryLoadWebsite = async () => {
  try {
    if (isVue.value) {
      // vue：加载构建产物预览（后端自动构建）
      const files = (await getAppCode({ id: appId.value as unknown as number })).data.data?.files ?? []
      previewFiles.value = files.map((f) => ({ path: f.path ?? '', content: f.content ?? '' }))
      if (previewFiles.value.length > 0) {
        selectedFilePath.value = previewFiles.value[0].path ?? ''
      }
      vueProgress.value = `已生成 ${previewFiles.value.length} 个文件`
      await loadVuePreview()
      return
    }
    const res = await getAppCode({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      const result = res.data.data
      previewDoc.value = buildPreviewDoc(result)
      const files = filesFromResult(result)
      previewFiles.value = files
      if (files.length > 0) {
        selectedFilePath.value = files[0].path
      }
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
  const aiMsg = reactive<ChatMessage>({ role: 'ai', content: '', status: 'streaming', files: [], reasoning: '' })
  messages.value.push(aiMsg)
  generating.value = true
  genProgress.value = '正在分析需求并生成代码…（可能需要 1-2 分钟）'
  previewLoading.value = true
  previewDoc.value = ''
  // 重置本次生成的预览状态：默认回到代码预览，生成完成前不可切网页预览
  previewMode.value = 'code'
  generationDone.value = false
  previewFiles.value = []
  selectedFilePath.value = ''
  exitVisualEdit()

  if (useStream.value) {
    // vue 模式：agent 逐文件生成，SSE 推送 agent_progress（每文件完整代码）
    if (isVue.value) {
      previewFiles.value = []
      vueProgress.value = '正在生成 Vue 工程…'
      let donePayload: API.AiCodeGenResultVO | null = null
      let vueFileCount = 0 // 已收到的文件数（进度展示用）
      // 推理增量先攒在本地（避免每 token 触发 reactive + MarkdownView 全量重渲染卡死），结束再赋值一次
      let reasoningBuf = ''

      // 收尾：description + 最终文件集 + 预览
      const finishVue = () => {
        const result = donePayload ?? ({ files: [] } as API.AiCodeGenResultVO)
        aiMsg.status = 'done'
        aiMsg.reasoning = reasoningBuf || undefined
        if (donePayload?.description) {
          aiMsg.content = donePayload.description
        }
        const finalFiles = filesFromResult(result)
        aiMsg.files = finalFiles
        setMsgUsage(aiMsg, donePayload)
        previewFiles.value = finalFiles
        if (previewFiles.value.length > 0) {
          selectedFilePath.value = previewFiles.value[0].path
        }
        vueProgress.value = `已生成 ${previewFiles.value.length} 个文件`
        previewLoading.value = false
        generating.value = false
        genProgress.value = ''
        generationDone.value = true
        // 生成完成后加载构建产物预览（后端自动构建 dist）
        vueProgress.value = '正在构建预览…'
        loadVuePreview()
      }

      const callbacks: SseCallbacks = {
        onAgentProgress: (data) => {
          // data 为 {"path":"...","content":"..."} JSON
          try {
            const parsed = JSON.parse(data)
            const path: string = parsed.path || ''
            const content: string = parsed.content || ''
            if (path) {
              vueFileCount++
              vueProgress.value = `正在生成 ${path}…`
              genProgress.value = `正在生成 ${path}…（第 ${vueFileCount} 个文件）`
              upsertFile(aiMsg.files!, { path, content })
              upsertFile(previewFiles.value, { path, content })
            }
          } catch {
            // 解析失败忽略（兼容旧格式）
          }
        },
        onReasoning: (text) => {
          reasoningBuf += text
        },
        onDone: (result) => {
          donePayload = result
          finishVue()
        },
        onError: (errorMsg) => {
          aiMsg.reasoning = reasoningBuf || undefined
          aiMsg.content = friendlyError(errorMsg)
          aiMsg.status = 'error'
          vueProgress.value = ''
          genProgress.value = ''
          previewLoading.value = false
          generating.value = false
          generationDone.value = true
        },
        onClose: () => {
          generating.value = false
          genProgress.value = ''
        },
      }
      connection = streamGenerateVueProject(appId.value, content, callbacks)
      return
    }
    // html/multi_file 模式：agent 逐文件生成，SSE 推送 agent_progress（每文件完整代码）。
    // 消息里只显示文件清单，完整代码放右侧预览（分文件文件树），避免大代码块重渲染卡顿。
    let donePayload: API.AiCodeGenResultVO | null = null
    let genFileCount = 0 // 已收到/显示的文件数（进度展示用）
    // 推理增量先攒在本地，结束再一次性赋值（避免每 token 触发 MarkdownView 全量重渲染卡死）
    let reasoningBuf = ''

    // 交付后渲染修复是异步的：轮询状态，修好了自动刷新预览
    // （生成响应只等到"检测"完成，修复在后台跑；旧的 SSE 已随 done 关闭，故用轮询）
    let renderFixTimer: number | null = null
    const stopRenderFixPolling = () => {
      if (renderFixTimer !== null) {
        window.clearInterval(renderFixTimer)
        renderFixTimer = null
      }
    }
    const startRenderFixPolling = () => {
      stopRenderFixPolling()
      let rounds = 0
      renderFixTimer = window.setInterval(async () => {
        rounds++
        try {
          const res = await getRenderFixStatus({ appId: appId.value })
          const status = res?.data
          if (status === 'fixing') {
            return
          }
          stopRenderFixPolling()
          if (status === 'fixed') {
            message.success('检测到渲染问题，已自动修复，正在刷新预览')
            await refreshAfterManualEdit()
          } else if (status === 'still_has_problems') {
            message.warning('渲染问题已尝试自动修复但仍有残留，建议手动检查')
            await refreshAfterManualEdit()
          }
        } catch {
          // 网络异常忽略，下一轮重试
        }
        // 兜底：最多轮询 40 次（约 200s），避免间隔无限挂着
        if (rounds >= 40) {
          stopRenderFixPolling()
        }
      }, 5000)
    }

    const finishAgent = () => {
      if (donePayload) {
        aiMsg.content = donePayload.description ?? ''
        aiMsg.status = 'done'
        aiMsg.reasoning = reasoningBuf || undefined
        previewDoc.value = buildPreviewDoc(donePayload)
        const finalFiles = filesFromResult(donePayload)
        aiMsg.files = finalFiles
        setMsgUsage(aiMsg, donePayload)
        previewFiles.value = finalFiles
        if (previewFiles.value.length > 0) {
          selectedFilePath.value = previewFiles.value[0].path
        }
      }
      previewLoading.value = false
      generating.value = false
      genProgress.value = ''
      generationDone.value = true
      // 交付后可能还有异步渲染修复在跑，开启轮询
      startRenderFixPolling()
    }
    const callbacks: SseCallbacks = {
      onAgentProgress: (data) => {
        // data 为 {"path":"...","content":"..."} JSON
        try {
          const parsed = JSON.parse(data)
          const path: string = parsed.path || ''
          const content: string = parsed.content || ''
          if (path) {
            genFileCount++
            genProgress.value = `正在生成 ${path}…（第 ${genFileCount} 个文件）`
            upsertFile(aiMsg.files!, { path, content })
            upsertFile(previewFiles.value, { path, content })
          }
        } catch {
          // 解析失败忽略（兼容旧格式）
        }
      },
      onReasoning: (text) => {
        reasoningBuf += text
      },
      onDone: (result) => {
        donePayload = result
        finishAgent()
      },
      onError: (errorMsg) => {
        aiMsg.reasoning = reasoningBuf || undefined
        aiMsg.content = friendlyError(errorMsg)
        aiMsg.status = 'error'
        previewLoading.value = false
        generating.value = false
        genProgress.value = ''
        generationDone.value = true
      },
      onClose: () => {
        generating.value = false
        genProgress.value = ''
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
        aiMsg.content = result.description ?? ''
        aiMsg.status = 'done'
        previewDoc.value = buildPreviewDoc(result)
        const finalFiles = filesFromResult(result)
        aiMsg.files = finalFiles
        setMsgUsage(aiMsg, result)
        previewFiles.value = finalFiles
        if (previewFiles.value.length > 0) {
          selectedFilePath.value = previewFiles.value[0].path
        }
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
      genProgress.value = ''
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

const buildPreviewDoc = (result: API.AiCodeGenResultVO): string => {
  // html 模式若拆分了 css/js 文件，也内联进预览（否则外链相对路径在 srcdoc 中失效）
  if (isMultiFile.value || (result.cssCode || result.jsCode)) {
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

// 从生成结果 VO 提取 token 用量并挂到消息上（气泡底部展示元信息）
const setMsgUsage = (msg: ChatMessage, result: API.AiCodeGenResultVO | null | undefined) => {
  if (!result || (result.promptTokens == null && result.totalTokens == null)) {
    return
  }
  msg.usage = {
    promptTokens: result.promptTokens ?? undefined,
    completionTokens: result.completionTokens ?? undefined,
    totalTokens: result.totalTokens ?? undefined,
  }
}

// 按 path 去重插入文件（同一文件可能被 agent 重写覆盖，保持清单唯一）
const upsertFile = (files: CodeFile[], file: CodeFile) => {
  const idx = files.findIndex((f) => f.path === file.path)
  if (idx >= 0) {
    files[idx] = file
  } else {
    files.push(file)
  }
}

// 从生成结果 VO 提取统一文件清单（vue 用 files；html/multi_file 拆成 index/css/js 三个文件）
const filesFromResult = (result: API.AiCodeGenResultVO): CodeFile[] => {
  const files: CodeFile[] = []
  if (result.files && result.files.length > 0) {
    for (const f of result.files) {
      if (f.path && f.content) {
        files.push({ path: f.path, content: f.content })
      }
    }
    return files
  }
  if (result.htmlCode) {
    files.push({ path: 'index.html', content: result.htmlCode })
  }
  if (result.cssCode) {
    files.push({ path: 'css/styles.css', content: result.cssCode })
  }
  if (result.jsCode) {
    files.push({ path: 'js/app.js', content: result.jsCode })
  }
  return files
}

// 从存库的 AI 消息 markdown（description + #### path + 代码块）解析出描述文本与文件清单，
// 供历史消息回显时还原"文件列表 + 点击查看"的展示
const parseMessageContent = (content: string): { description: string; files: CodeFile[] } => {
  const files: CodeFile[] = []
  const descParts: string[] = []
  const blockRe = /####\s+(.+?)\s*\n```[a-zA-Z]*\n([\s\S]*?)```/g
  let lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = blockRe.exec(content))) {
    if (m.index > lastIndex) {
      descParts.push(content.slice(lastIndex, m.index))
    }
    files.push({ path: m[1].trim(), content: m[2] })
    lastIndex = blockRe.lastIndex
  }
  descParts.push(content.slice(lastIndex))
  return { description: descParts.join('').trim(), files }
}

// 文件大小格式化（字节 → KB）
const formatSize = (bytes: number): string => {
  if (bytes < 1024) {
    return bytes + ' B'
  }
  return (bytes / 1024).toFixed(1) + ' KB'
}

// 点击对话消息里的文件清单 → 在右侧预览打开该文件（历史消息与当前预览不一致时以该消息文件集为准）
const openFileFromMessage = (msg: ChatMessage, path: string) => {
  if (!msg.files?.some((f) => f.path === path)) {
    return
  }
  previewFiles.value = [...msg.files]
  selectedFilePath.value = path
  previewMode.value = 'code'
}

// 跳转到已部署的应用网站（deployUrl 由后端组装，域名 + deployKey）
const openDeployedSite = () => {
  if (app.value?.deployUrl) {
    window.open(app.value.deployUrl, '_blank')
  } else {
    message.warning('部署地址不可用')
  }
}

// 下载应用源码 zip（后端校验创建者权限并过滤排除敏感/构建文件）
const handleDownloadCode = async () => {
  if (!app.value?.id) return
  try {
    const res = await downloadCode({ id: appId.value as unknown as number })
    const blob = res.data as Blob
    // 后端异常时返回的是 JSON blob（如未生成/无权限），识别出来提示而不是当文件下载
    if (blob && blob.type && blob.type.includes('json')) {
      try {
        const parsed = JSON.parse(await blob.text())
        message.error(parsed.message || '下载失败')
      } catch {
        message.error('下载失败')
      }
      return
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${app.value.appName || 'app'}_${app.value.codeGenType || 'code'}.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    message.success('已开始下载')
  } catch (e: any) {
    message.error(e?.message || '下载失败')
  }
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
        deployStatus.value = ''
        deploying.value = false
        await loadApp()
      } else {
        message.error(res.data.message || '下线失败')
        deployStatus.value = ''
        deploying.value = false
      }
      return
    }
    // 未部署 → 上线
    if (isVue.value) {
      // vue 模式：流式部署（构建 + 自动修复进度）
      deployStatus.value = '正在准备部署…'
      const deployConn = createSseConnection(
        '/app/deploy/stream',
        { appId: app.value.id },
        {
          onDeployProgress: (text) => {
            deployStatus.value = deployStatusText(text)
          },
          onDone: (result: any) => {
            deploying.value = false
            deployStatus.value = '部署成功'
            message.success('部署成功')
            loadApp()
            // done 事件 data 为 {"url":"http://localhost/xxx"}
            const url = result?.url || ''
            if (url) {
              window.open(url, '_blank')
            }
          },
          onError: (errorMsg) => {
            deploying.value = false
            deployStatus.value = ''
            message.error(errorMsg || '部署失败')
          },
          onClose: () => {
            deploying.value = false
          },
        },
      )
      // 保持引用防 GC；onDone 里完成
      deployConn
      return
    }
    const res = await deployApp({ appId: app.value.id })
    if (res.data.code === 0 && res.data.data) {
      message.success('部署成功')
      await loadApp()
      window.open(res.data.data, '_blank')
    } else {
      message.error(res.data.message || '部署失败')
    }
  } catch {
    message.error('操作失败')
  } finally {
    if (!isVue.value) deploying.value = false
  }
}

// 部署进度文本映射
const deployStatusText = (text: string): string => {
  if (text === 'START_INSTALL') return '正在安装依赖（npm install）…'
  if (text === 'START_BUILD') return '正在构建（npm run build）…'
  if (text === 'BUILD_OK') return '构建完成'
  if (text.startsWith('BUILD_FAILED')) return '构建失败，正在自动修复…'
  if (text.startsWith('FIXED|')) return `已修复 ${text.slice(6)}，重新构建…`
  if (text === 'REBUILDING') return '正在重新构建…'
  if (text.startsWith('FIX_FAILED')) return '自动修复失败'
  return text
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
  exitVisualEdit()
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
        <a-tag v-if="app && isVue" color="green">Vue3 工程</a-tag>
        <a-tag v-else-if="app && isMultiFile" color="purple">多文件</a-tag>
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
          v-if="app?.deployKey"
          @click="openDeployedSite"
        >
          <template #icon><LinkOutlined /></template>
          打开网站
        </a-button>
        <a-button @click="handleDownloadCode">
          <template #icon><DownloadOutlined /></template>
          下载代码
        </a-button>
        <a-button
          type="primary"
          :loading="deploying"
          @click="handleDeploy"
        >
          {{ app?.deployKey ? '下线' : '部署' }}
        </a-button>
        <span v-if="deployStatus" class="deploy-status">{{ deployStatus }}</span>
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
              <template v-if="msg.role === 'ai'">
                <details v-if="msg.reasoning" class="msg-reasoning">
                  <summary>💭 思考过程（{{ msg.reasoning.length }}字）</summary>
                  <!-- 纯文本渲染，避免大段推理走 MarkdownView 全量解析造成卡顿 -->
                  <pre class="msg-reasoning-text">{{ msg.reasoning }}</pre>
                </details>
                <MarkdownView v-if="msg.content" :content="msg.content" />
                <div v-if="msg.files && msg.files.length" class="msg-file-list">
                  <div
                    v-for="f in msg.files"
                    :key="f.path"
                    class="msg-file-item"
                    title="点击在右侧查看完整代码"
                    @click="openFileFromMessage(msg, f.path)"
                  >
                    <FileTextOutlined class="msg-file-icon" />
                    <span class="msg-file-path">{{ f.path }}</span>
                    <span class="msg-file-size">{{ formatSize(f.content.length) }}</span>
                  </div>
                </div>
                <div v-if="msg.usage && msg.usage.totalTokens" class="msg-usage">
                  消耗 {{ msg.usage.totalTokens }} tokens<span
                    v-if="msg.usage.promptTokens != null"
                  > · 输入 {{ msg.usage.promptTokens }}</span><span
                    v-if="msg.usage.completionTokens != null"
                  > · 输出 {{ msg.usage.completionTokens }}</span>
                </div>
              </template>
              <span v-else>{{ msg.content }}</span>
            </div>
          </div>
        </div>
        <div v-if="generating && genProgress" class="gen-progress-bar">
          <a-spin size="small" />
          <span class="gen-progress-text">{{ genProgress }}</span>
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
          <a-space size="small">
            <a-select
              v-model:value="previewMode"
              style="width: 110px"
              :options="[
                { label: '代码预览', value: 'code' },
                { label: '网页预览', value: 'web' },
              ]"
              :disabled="!generationDone"
            />
            <a-button
              v-if="previewMode === 'web' && generationDone && !isVue"
              size="small"
              :type="editMode ? 'primary' : 'default'"
              :disabled="modifying"
              @click="toggleVisualEdit"
            >
              {{ editMode ? '退出编辑' : '可视化编辑' }}
            </a-button>
            <a-button
              v-if="undoSnapshot.length"
              size="small"
              :disabled="modifying"
              @click="undoLastModify"
            >
              撤销修改
            </a-button>
            <a-spin v-if="previewLoading || modifying" size="small" />
          </a-space>
          <span v-if="editStatus" class="deploy-status">{{ editStatus }}</span>
        </div>
        <div class="preview-content">
          <template v-if="previewMode === 'code'">
            <div class="vue-code-layout">
              <div class="vue-file-tree">
                <div v-if="vueProgress" class="vue-progress">{{ vueProgress }}</div>
                <div
                  v-for="f in previewFiles"
                  :key="f.path"
                  class="vue-file-item"
                  :class="{ active: f.path === selectedFilePath }"
                  @click="selectFile(f.path)"
                >
                  {{ f.path }}
                </div>
                <a-empty v-if="previewFiles.length === 0" :description="vueProgress || '暂无文件'" :image="false" />
              </div>
              <MarkdownView
                v-if="selectedFileContent"
                :content="selectedFileMarkdown"
                class="preview-md"
              />
              <div v-else class="preview-empty">
                <a-empty
                  :description="previewLoading ? '正在生成代码，请稍候...' : '代码生成后在此展示'"
                />
              </div>
            </div>
          </template>
          <template v-else>
            <template v-if="previewDoc">
              <div class="web-preview-wrap">
                <iframe
                  ref="previewFrameRef"
                  :srcdoc="previewDoc"
                  class="preview-frame"
                  sandbox="allow-scripts allow-same-origin allow-modals allow-forms allow-popups"
                  @load="onPreviewFrameLoad"
                />
                <div v-if="editMode && !editTarget" class="edit-hint">
                  点击网页中的元素进行选择，点「退出编辑」结束
                </div>
                <div v-if="editTarget" class="edit-card">
                  <div class="edit-card-head">
                    <span class="edit-card-tag">{{ editTarget.tag }}</span>
                    <a-button type="text" size="small" class="edit-card-close" @click="cancelEditTarget">
                      ✕
                    </a-button>
                  </div>
                  <div v-if="editTarget.text" class="edit-card-text">“{{ editTarget.text }}”</div>
                  <div class="edit-card-selector">{{ editTarget.selector }}</div>

                  <div class="edit-tabs">
                    <div class="edit-tab" :class="{ active: editTab === 'style' }" @click="editTab = 'style'">
                      样式
                    </div>
                    <div class="edit-tab" :class="{ active: editTab === 'ai' }" @click="editTab = 'ai'">
                      AI 修改
                    </div>
                  </div>

                  <!-- 样式面板：属性改动即时在 iframe 内联预览，点「应用到源码」才落盘 -->
                  <template v-if="editTab === 'style'">
                    <div class="style-panel">
                      <div v-for="f in STYLE_FIELDS" :key="f.css" class="style-row">
                        <span class="style-label">{{ f.label }}</span>
                        <input
                          v-if="f.kind === 'color'"
                          type="color"
                          class="style-color"
                          :value="toHexColor(styleVal[f.css])"
                          @input="onStyleInput(f.css, $event)"
                        />
                        <input
                          v-else-if="f.kind === 'px' || f.kind === 'num'"
                          type="number"
                          class="style-num"
                          :step="f.kind === 'num' ? '0.1' : '1'"
                          :min="f.kind === 'num' ? '0.5' : '0'"
                          :value="styleVal[f.css]"
                          @input="onStyleInput(f.css, $event)"
                        />
                        <select
                          v-else-if="f.kind === 'weight'"
                          class="style-select"
                          :value="styleVal[f.css]"
                          @change="onStyleSelect(f.css, $event)"
                        >
                          <option value="normal">常规</option>
                          <option value="bold">加粗</option>
                          <option value="400">400</option>
                          <option value="500">500</option>
                          <option value="600">600</option>
                          <option value="700">700</option>
                        </select>
                        <select
                          v-else-if="f.kind === 'align'"
                          class="style-select"
                          :value="styleVal[f.css]"
                          @change="onStyleSelect(f.css, $event)"
                        >
                          <option value="left">左对齐</option>
                          <option value="center">居中</option>
                          <option value="right">右对齐</option>
                          <option value="justify">两端对齐</option>
                        </select>
                        <input
                          v-else-if="f.kind === 'size'"
                          type="text"
                          class="style-num"
                          placeholder="auto / 100% / 300px"
                          :value="styleVal[f.css]"
                          @input="onStyleInput(f.css, $event)"
                        />
                      </div>
                    </div>
                    <div class="style-divider">文字内容（整段替换）</div>
                    <textarea
                      v-model="styleText"
                      class="style-text"
                      :rows="2"
                      :disabled="styleBusy"
                      placeholder="输入该元素的新文字…"
                      @input="onTextInput"
                    />
                    <div class="style-text-row">
                      <a-button
                        size="small"
                        type="primary"
                        ghost
                        :loading="styleBusy"
                        :disabled="!styleText.trim()"
                        @click="applyManualText"
                      >
                        改文字
                      </a-button>
                      <span class="style-tip">会把该元素内部的文字整体替换</span>
                    </div>
                    <div class="edit-card-actions">
                      <a-button size="small" :disabled="styleBusy" @click="initStylePanel">重置</a-button>
                      <a-button
                        size="small"
                        type="primary"
                        :loading="styleBusy"
                        @click="applyManualStyle"
                      >
                        应用到源码
                      </a-button>
                    </div>
                  </template>

                  <!-- AI 修改：自然语言 -->
                  <template v-else>
                    <a-textarea
                      v-model:value="editInstruction"
                      placeholder="想怎么改这个元素？如：把标题改成「XX」、文字改成蓝色、加一个按钮…（Ctrl+Enter 应用）"
                      :auto-size="{ minRows: 2, maxRows: 4 }"
                      :disabled="modifying"
                      @keydown="handleEditKeydown"
                    />
                    <div class="edit-card-actions">
                      <a-button size="small" :disabled="modifying" @click="cancelEditTarget">
                        取消选择
                      </a-button>
                      <a-button
                        size="small"
                        type="primary"
                        :loading="modifying"
                        @click="applyModify"
                      >
                        {{ modifying ? '修改中…' : '应用修改' }}
                      </a-button>
                    </div>
                  </template>

                  <!-- 手动修改记录（逐条撤销 / 清空） -->
                  <div v-if="manualEdits.length" class="edit-history">
                    <div class="edit-history-head">
                      <span>手动修改记录（{{ manualEdits.length }}）</span>
                      <a-button type="link" size="small" :disabled="styleBusy" @click="clearAllManual">
                        清空
                      </a-button>
                    </div>
                    <div class="edit-history-list">
                      <div v-for="m in manualEdits" :key="m.id" class="edit-history-item">
                        <span class="edit-history-kind" :class="m.kind">
                          {{ m.kind === 'text' ? '文字' : '样式' }}
                        </span>
                        <div class="edit-history-sel" :title="m.content">{{ m.selector }}</div>
                        <a-button
                          type="text"
                          size="small"
                          :disabled="styleBusy"
                          @click="undoOneManual(m.id)"
                        >
                          撤销
                        </a-button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>
            <div v-else class="preview-empty">
              <a-empty
                :description="vuePreviewLoading ? '正在构建预览，请稍候...' : previewLoading ? '正在生成，请稍候...' : '生成完成后将在此展示网页效果'"
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

.deploy-status {
  font-size: 13px;
  color: #1677ff;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.msg-file-list {
  margin-top: 8px;
  border-top: 1px dashed #d9d9d9;
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg-file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  color: #1677ff;
  background: #fff;
  transition: background 0.2s;
}

.msg-file-item:hover {
  background: #e6f4ff;
}

.msg-file-icon {
  color: #8c8c8c;
}

.msg-file-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-file-size {
  font-size: 12px;
  color: #999;
  flex-shrink: 0;
}

.msg-usage {
  margin-top: 6px;
  font-size: 12px;
  color: #999;
}

.msg-reasoning {
  margin: 0 0 8px;
  padding: 6px 10px;
  background: #fafafa;
  border: 1px solid #eee;
  border-radius: 6px;
  font-size: 12px;
}

.msg-reasoning summary {
  cursor: pointer;
  color: #666;
  user-select: none;
  outline: none;
}

.msg-reasoning-text {
  margin-top: 6px;
  max-height: 180px;
  overflow-y: auto;
  color: #888;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
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

.gen-progress-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
  flex-shrink: 0;
}

.gen-progress-text {
  font-size: 13px;
  color: #666;
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

.web-preview-wrap {
  position: relative;
  height: 100%;
}

.edit-hint {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 14px;
  border-radius: 14px;
  background: rgba(22, 119, 255, 0.12);
  border: 1px solid rgba(22, 119, 255, 0.35);
  color: #1677ff;
  font-size: 13px;
  pointer-events: none;
  z-index: 10;
  white-space: nowrap;
}

.edit-card {
  position: absolute;
  top: 10px;
  right: 12px;
  width: 300px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  padding: 10px 12px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.edit-card-title {
  font-weight: 600;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.edit-card-tag {
  font-weight: 400;
  color: #1677ff;
  background: #e6f4ff;
  border-radius: 4px;
  padding: 0 6px;
  font-family: 'Consolas', monospace;
  font-size: 12px;
}

.edit-card-text {
  font-size: 12px;
  color: #666;
  background: #fafafa;
  border-radius: 4px;
  padding: 4px 8px;
  word-break: break-all;
  max-height: 60px;
  overflow-y: auto;
}

.edit-card-selector {
  font-size: 11px;
  color: #999;
  font-family: 'Consolas', monospace;
  word-break: break-all;
}

.edit-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.edit-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.edit-card-close {
  color: #999;
}

.edit-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid #f0f0f0;
}

.edit-tab {
  padding: 4px 10px;
  font-size: 13px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  user-select: none;
}

.edit-tab.active {
  color: #1677ff;
  border-bottom-color: #1677ff;
  font-weight: 500;
}

.style-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
}

.style-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.style-label {
  font-size: 12px;
  color: #555;
  flex-shrink: 0;
}

.style-color {
  width: 34px;
  height: 22px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

.style-num {
  width: 110px;
  font-size: 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 6px;
  text-align: right;
}

.style-select {
  width: 110px;
  font-size: 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 4px;
}

.edit-history {
  margin-top: 8px;
  border-top: 1px dashed #eee;
  padding-top: 6px;
}

.edit-history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #666;
}

.edit-history-list {
  max-height: 110px;
  overflow-y: auto;
}

.edit-history-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 2px 0;
}

.edit-history-sel {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'Consolas', monospace;
  font-size: 11px;
  color: #888;
}

.edit-history-kind {
  flex-shrink: 0;
  font-size: 10px;
  border-radius: 3px;
  padding: 0 4px;
  line-height: 16px;
}

.edit-history-kind.style {
  background: #e6f4ff;
  color: #1677ff;
}

.edit-history-kind.text {
  background: #fff7e6;
  color: #d46b08;
}

.style-divider {
  margin-top: 6px;
  font-size: 11px;
  color: #999;
  border-top: 1px dashed #eee;
  padding-top: 6px;
}

.style-text {
  width: 100%;
  font-size: 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 6px;
  resize: vertical;
  box-sizing: border-box;
}

.style-text-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.style-tip {
  font-size: 11px;
  color: #bbb;
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

.preview-md {
  height: 100%;
  overflow: auto;
  padding: 16px;
}

.preview-md :deep(pre) {
  margin: 0;
}

.preview-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.vue-code-layout {
  display: flex;
  height: 100%;
}

.vue-file-tree {
  width: 220px;
  border-right: 1px solid #f0f0f0;
  overflow-y: auto;
  padding: 8px 0;
  background: #fafafa;
  flex-shrink: 0;
}

.vue-progress {
  padding: 4px 12px;
  font-size: 12px;
  color: #666;
}

.vue-file-item {
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: background 0.2s;
}

.vue-file-item:hover {
  background: #f0f5ff;
}

.vue-file-item.active {
  background: #e6f4ff;
  color: #1677ff;
  font-weight: 500;
}

.vue-code {
  flex: 1;
  min-width: 0;
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
