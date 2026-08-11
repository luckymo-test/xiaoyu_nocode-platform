<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { listChatAppByAdmin, listAllByAdmin } from '@/api/chatHistoryController'
import MarkdownView from '@/components/MarkdownView.vue'

const searchForm = reactive<{
  appId?: number
  userId?: number
}>({
  appId: undefined,
  userId: undefined,
})

// 应用列表（一个应用一行）
const appList = ref<API.ChatAppVO[]>([])
const loading = ref(false)
const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
})

// 抽屉：某应用的全部对话历史
const drawerVisible = ref(false)
const currentApp = ref<API.ChatAppVO | null>(null)
const drawerMsgs = ref<API.ChatHistory[]>([])
const drawerMsgLoading = ref(false)
const hasMoreMsg = ref(false)
const drawerMsgPage = ref(1)
const DRAWER_PAGE_SIZE = 50

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listChatAppByAdmin({
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      appId: searchForm.appId,
      userId: searchForm.userId,
    })
    if (res.data.code === 0 && res.data.data) {
      appList.value = res.data.data.records ?? []
      pagination.total = Number(res.data.data.totalRow ?? 0)
    }
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pagination.current = 1
  fetchData()
}

const handleReset = () => {
  searchForm.appId = undefined
  searchForm.userId = undefined
  handleSearch()
}

const handlePageChange = (page: number) => {
  pagination.current = page
  fetchData()
}

const messageRole = (type?: string): 'user' | 'ai' =>
  type === 'user' ? 'user' : 'ai'

// 导出：是否正在生成/下载
const exporting = ref(false)

// 循环翻页加载该应用全部对话，按时间正序返回（后端接口返回降序）
const loadAllMessages = async (appId: number): Promise<API.ChatHistory[]> => {
  const all: API.ChatHistory[] = []
  let page = 1
  const pageSize = 200
  for (;;) {
    const res = await listAllByAdmin({ appId, pageNum: page, pageSize })
    const records = res.data.data?.records ?? []
    all.push(...records)
    if (records.length < pageSize) break
    page++
  }
  all.sort((a, b) => {
    const t = (a.createTime ?? '').localeCompare(b.createTime ?? '')
    return t !== 0 ? t : Number(a.id ?? 0) - Number(b.id ?? 0)
  })
  return all
}

const formatTime = (time?: string) => (time ?? '').replace('T', ' ').slice(0, 19)

// 将全部对话拼成 Markdown：头部元信息 + 按时间正序的完整对话
const buildMarkdown = (app: API.ChatAppVO, msgs: API.ChatHistory[]): string => {
  const lines: string[] = []
  lines.push(`# 对话记录 - ${app.appName || '应用'}`)
  lines.push('')
  lines.push(`- 应用ID：${app.appId}`)
  lines.push(`- 用户ID：${app.userId}`)
  if (msgs.length > 0) {
    lines.push(
      `- 时间范围：${formatTime(msgs[0].createTime)} ~ ${formatTime(msgs[msgs.length - 1].createTime)}`,
    )
  }
  lines.push('')
  lines.push('---')
  lines.push('')
  for (const msg of msgs) {
    const roleName =
      msg.messageType === 'user' ? '用户' : msg.messageType === 'assistant' ? 'AI' : '错误'
    lines.push(`## ${roleName} · ${formatTime(msg.createTime)}`)
    lines.push('')
    lines.push(msg.message ?? '')
    lines.push('')
    lines.push('---')
    lines.push('')
  }
  return lines.join('\n')
}

// 导出当前应用全部对话为 Markdown 文件下载
const handleExport = async () => {
  if (exporting.value || !currentApp.value?.appId) return
  exporting.value = true
  try {
    const msgs = await loadAllMessages(currentApp.value.appId)
    if (msgs.length === 0) {
      return
    }
    const markdown = buildMarkdown(currentApp.value, msgs)
    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    const name = (currentApp.value.appName || `app-${currentApp.value.appId}`).replace(
      /[\\/:*?"<>|]/g,
      '_',
    )
    a.href = url
    a.download = `对话记录-${name}.md`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

// 打开抽屉：加载该应用最新一页消息（降序取回后反转为时间正序）
const handleView = async (record: API.ChatAppVO) => {
  currentApp.value = record
  drawerMsgs.value = []
  drawerMsgPage.value = 1
  hasMoreMsg.value = false
  drawerVisible.value = true
  await loadMoreMessages(true)
}

// 加载更早一页消息，追加到当前已加载列表之前（保持时间正序）
const loadMoreMessages = async (fresh = false) => {
  if (drawerMsgLoading.value || !currentApp.value?.appId) return
  drawerMsgLoading.value = true
  try {
    const res = await listAllByAdmin({
      appId: currentApp.value.appId,
      pageNum: drawerMsgPage.value,
      pageSize: DRAWER_PAGE_SIZE,
    })
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records ?? []
      // 后端时间降序，反转为正序展示；非首次加载时拼到已加载消息之前
      const ordered = [...records].reverse()
      if (fresh) {
        drawerMsgs.value = ordered
      } else {
        drawerMsgs.value = [...ordered, ...drawerMsgs.value]
      }
      hasMoreMsg.value = records.length === DRAWER_PAGE_SIZE
      drawerMsgPage.value += 1
    } else {
      hasMoreMsg.value = false
    }
  } finally {
    drawerMsgLoading.value = false
  }
}

onMounted(() => {
  fetchData()
})

const columns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName' },
  { title: '应用ID', dataIndex: 'appId', key: 'appId', width: 200 },
  { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 200 },
  { title: '消息数', dataIndex: 'messageCount', key: 'messageCount', width: 100 },
  {
    title: '最近对话时间',
    dataIndex: 'lastCreateTime',
    key: 'lastCreateTime',
    width: 180,
  },
  { title: '操作', key: 'action', width: 100, fixed: 'right' },
]
</script>

<template>
  <div class="admin-chat-page">
    <a-card :bordered="false">
      <div class="search-bar">
        <a-space>
          <a-input-number
            v-model:value="searchForm.appId"
            placeholder="应用ID"
            :min="1"
            :controls="false"
            style="width: 180px"
            @keydown.enter="handleSearch"
          />
          <a-input-number
            v-model:value="searchForm.userId"
            placeholder="用户ID"
            :min="1"
            :controls="false"
            style="width: 180px"
            @keydown.enter="handleSearch"
          />
          <a-button type="primary" @click="handleSearch">搜索</a-button>
          <a-button @click="handleReset">重置</a-button>
        </a-space>
      </div>

      <a-table
        :columns="columns"
        :data-source="appList"
        :loading="loading"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: false,
        }"
        row-key="appId"
        :scroll="{ x: 900 }"
        @change="({ current }: any) => handlePageChange(current)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-button type="link" size="small" @click="handleView(record)">
              查看对话
            </a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-drawer
      v-model:open="drawerVisible"
      :title="`对话历史 - ${currentApp?.appName || '应用'}`"
      width="760px"
      destroy-on-close
    >
      <template #extra>
        <a-space>
          <a-tag v-if="currentApp">共 {{ currentApp.messageCount }} 条</a-tag>
          <a-button
            type="primary"
            size="small"
            :loading="exporting"
            :disabled="!currentApp || (currentApp.messageCount ?? 0) === 0"
            @click="handleExport"
          >
            导出 Markdown
          </a-button>
        </a-space>
      </template>
      <div v-if="currentApp" class="drawer-meta">
        <span>应用ID：{{ currentApp.appId }}</span>
        <span>用户ID：{{ currentApp.userId }}</span>
        <span>最近对话：{{ currentApp.lastCreateTime }}</span>
      </div>
      <div class="drawer-body">
        <template v-if="drawerMsgs.length === 0">
          <div class="drawer-empty">
            <a-empty description="暂无对话记录" />
          </div>
        </template>
        <template v-else>
          <div v-if="hasMoreMsg" class="load-more-bar">
            <a-button
              type="link"
              size="small"
              :loading="drawerMsgLoading"
              @click="loadMoreMessages()"
            >
              加载更多
            </a-button>
          </div>
          <div class="msg-list">
            <div
              v-for="(msg, index) in drawerMsgs"
              :key="msg.id ?? index"
              class="msg-row"
              :class="messageRole(msg.messageType)"
            >
              <a-avatar class="msg-avatar" :class="messageRole(msg.messageType)">
                {{ messageRole(msg.messageType) === 'user' ? 'U' : 'AI' }}
              </a-avatar>
              <div class="msg-bubble" :class="messageRole(msg.messageType)">
                <MarkdownView
                  v-if="msg.messageType === 'assistant'"
                  :content="msg.message ?? ''"
                />
                <span v-else>{{ msg.message }}</span>
              </div>
              <span class="msg-time">{{ msg.createTime }}</span>
            </div>
          </div>
        </template>
      </div>
    </a-drawer>
  </div>
</template>

<style scoped>
.admin-chat-page {
  margin: 0 auto;
}

.search-bar {
  margin-bottom: 16px;
}

.drawer-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 12px;
  color: #666;
  font-size: 13px;
}

.drawer-body {
  height: calc(100vh - 220px);
  overflow-y: auto;
}

.drawer-empty {
  padding: 48px 0;
}

.load-more-bar {
  display: flex;
  justify-content: center;
  margin-bottom: 8px;
}

.msg-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  flex-shrink: 0;
  margin-top: 2px;
}

.msg-avatar.user {
  background: #1677ff;
}

.msg-avatar.ai {
  background: #8c8c8c;
}

.msg-bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
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

.msg-row.ai .msg-bubble :deep(.markdown-body) {
  color: #333;
}

.msg-time {
  font-size: 12px;
  color: #999;
  align-self: flex-end;
  flex-shrink: 0;
  margin-top: 10px;
}
</style>
