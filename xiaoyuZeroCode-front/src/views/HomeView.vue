<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { appMessage as message } from '@/utils/feedback'
import {
  DownOutlined,
  PlusOutlined,
  RocketOutlined,
  SearchOutlined,
  ThunderboltFilled,
} from '@ant-design/icons-vue'
import {
  addApp,
  deleteApp,
  listFeaturedAppVoByPage,
  listMyAppVoByPage,
  listPublicAppVoByPage,
} from '@/api/appController'
import { listRuntimeProfiles, type RuntimeProfile } from '@/api/runtimeProfile'
import AppCard from '@/components/AppCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import {
  NEW_APP_REASONING_STORAGE_KEY,
  parseReasoningIndex,
  REASONING_LEVELS,
  REASONING_MARKS,
} from '@/config/reasoning'

const router = useRouter()

/* ---------------- 创作台 ---------------- */

const prompt = ref('')
const creating = ref(false)

/** 生成模式：single / multi / single-stream / multi-stream */
interface GenerateMode {
  label: string
  codeGenType: string
  stream: boolean
  stackProfileId?: string
}

const generateModes = ref<GenerateMode[]>([
  { label: '单文件生成', codeGenType: 'html', stream: false },
  { label: '多文件生成', codeGenType: 'multi_file', stream: false },
  { label: '单文件流式生成', codeGenType: 'html', stream: true },
  { label: '多文件流式生成', codeGenType: 'multi_file', stream: true },
  { label: 'Vue3 工程生成', codeGenType: 'vue', stream: true },
])
const runtimeProfiles = ref<RuntimeProfile[]>([])

const selectedModeIndex = ref(2)
const selectedMode = computed(() => generateModes.value[selectedModeIndex.value])
const reasoningIndex = ref(
  parseReasoningIndex(localStorage.getItem(NEW_APP_REASONING_STORAGE_KEY)),
)
const reasoningLevel = computed(() => REASONING_LEVELS[reasoningIndex.value]?.value ?? 'standard')
const reasoningLabel = computed(() => REASONING_LEVELS[reasoningIndex.value]?.label ?? '标准')
const reasoningMarks = REASONING_MARKS
watch(reasoningIndex, (value) => {
  localStorage.setItem(
    NEW_APP_REASONING_STORAGE_KEY,
    REASONING_LEVELS[value]?.value ?? 'standard',
  )
})

/** 创建时的可见范围（默认公开） */
const createVisibility = ref<'public' | 'private'>('public')
const visibilityOptions = [
  { label: '公开', value: 'public' },
  { label: '私有', value: 'private' },
]
const modeOptions = computed(() =>
  generateModes.value.map((mode, index) => ({ label: mode.label, value: index })),
)

/** 冷启动引导：点一下就能填进输入框 */
const examplePrompts = [
  '生成一个简洁美观的个人计划页面',
  '做一个产品落地页，带功能区和价格表',
  '写一个番茄钟计时器，支持暂停',
  '做一个数据看板，展示销售趋势',
]

const handleCreate = async () => {
  const text = prompt.value.trim()
  if (!text) {
    message.warning('请先描述你想创建的网站应用')
    return
  }
  creating.value = true
  try {
    // 应用名取描述前 12 个字符，避免过长
    const appName = text.replace(/\s+/g, ' ').slice(0, 12)
    const res = await addApp({
      appName,
      initPrompt: text,
      codeGenType: selectedMode.value.codeGenType,
      visibility: createVisibility.value,
    })
    if (res.data.code === 0 && res.data.data) {
      message.success('创建成功，正在生成应用...')
      const profileQuery = selectedMode.value.stackProfileId
        ? `&stackProfile=${encodeURIComponent(selectedMode.value.stackProfileId)}`
        : ''
      router.push(
        `/app/gen/${res.data.data}?stream=${selectedMode.value.stream}`
          + `&reasoning=${reasoningLevel.value}${profileQuery}`,
      )
    } else {
      message.error(res.data.message || '创建失败')
    }
  } catch {
    message.error('创建失败')
  } finally {
    creating.value = false
  }
}

/* ---------------- 三个列表：按 Tab 懒加载 ---------------- */

const activeTab = ref<'mine' | 'featured' | 'public'>('mine')
const tabLoaded = reactive<Record<string, boolean>>({})

// 我的应用
const myAppList = ref<API.AppVO[]>([])
const myAppLoading = ref(false)
const myAppPagination = reactive({ current: 1, pageSize: 8, total: 0 })
const myAppKeyword = ref('')

// 精选应用
const featuredList = ref<API.AppVO[]>([])
const featuredLoading = ref(false)
const featuredPagination = reactive({ current: 1, pageSize: 8, total: 0 })
const featuredKeyword = ref('')

// 公开应用
const publicList = ref<API.AppVO[]>([])
const publicLoading = ref(false)
const publicPagination = reactive({ current: 1, pageSize: 12, total: 0 })
const publicKeyword = ref('')
const publicTag = ref<string | undefined>(undefined)
const hotTags = ['个人', '电商', '时钟', '登录', '计划', '天气']

const fetchMyApps = async () => {
  myAppLoading.value = true
  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppPagination.current,
      pageSize: myAppPagination.pageSize,
      appName: myAppKeyword.value.trim() || undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      myAppList.value = res.data.data.records ?? []
      myAppPagination.total = Number(res.data.data.totalRow ?? 0)
    }
  } finally {
    myAppLoading.value = false
  }
}

const fetchFeaturedApps = async () => {
  featuredLoading.value = true
  try {
    const res = await listFeaturedAppVoByPage({
      pageNum: featuredPagination.current,
      pageSize: featuredPagination.pageSize,
      appName: featuredKeyword.value.trim() || undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      featuredList.value = res.data.data.records ?? []
      featuredPagination.total = Number(res.data.data.totalRow ?? 0)
    }
  } finally {
    featuredLoading.value = false
  }
}

// 公开应用：searchText 支持名称/描述/标签多维搜索，tags 精确筛选
const fetchPublicApps = async () => {
  publicLoading.value = true
  try {
    const res = await listPublicAppVoByPage({
      pageNum: publicPagination.current,
      pageSize: publicPagination.pageSize,
      searchText: publicKeyword.value.trim() || undefined,
      tags: publicTag.value || undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      publicList.value = res.data.data.records ?? []
      publicPagination.total = Number(res.data.data.totalRow ?? 0)
    }
  } finally {
    publicLoading.value = false
  }
}

const loadTab = (key: string) => {
  if (tabLoaded[key]) return
  tabLoaded[key] = true
  if (key === 'mine') fetchMyApps()
  if (key === 'featured') fetchFeaturedApps()
  if (key === 'public') fetchPublicApps()
}

const handleTabChange = (key: string | number) => {
  loadTab(String(key))
}

/* ---------------- 交互 ---------------- */

const handlePublicSearch = () => {
  publicPagination.current = 1
  fetchPublicApps()
}

const handlePublicTagClick = (tag: string) => {
  publicTag.value = publicTag.value === tag ? undefined : tag
  handlePublicSearch()
}

const handleOpenApp = (id?: number) => {
  if (!id) return
  router.push(`/app/gen/${id}`)
}

const handleEdit = (id?: number) => {
  if (!id) return
  router.push(`/app/edit/${id}`)
}

/** 删除走二次确认弹窗：不可逆操作不用裸链接 */
const deleteModalOpen = ref(false)
const deleteTarget = ref<API.AppVO | null>(null)
const deleting = ref(false)

const askDelete = (record: API.AppVO) => {
  deleteTarget.value = record
  deleteModalOpen.value = true
}

const confirmDelete = async () => {
  const id = deleteTarget.value?.id
  if (!id) return
  deleting.value = true
  try {
    const res = await deleteApp({ id })
    if (res.data.code === 0) {
      message.success('已删除到回收站')
      deleteModalOpen.value = false
      fetchMyApps()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch {
    message.error('删除失败')
  } finally {
    deleting.value = false
  }
}

const handleMoreClick = ({ key }: { key: string | number }, record: API.AppVO) => {
  if (key === 'edit') handleEdit(record.id)
  if (key === 'delete') askDelete(record)
}

const focusStudio = () => {
  document.querySelector<HTMLTextAreaElement>('.studio-input')?.focus()
}

onMounted(() => {
  loadTab('mine')
  void listRuntimeProfiles().then((res) => {
    runtimeProfiles.value = res.code === 0 ? res.data : []
    if (runtimeProfiles.value.some((profile) =>
      profile.id === 'react-vite-ts' && profile.available
    ) && !generateModes.value.some((mode) => mode.stackProfileId === 'react-vite-ts')) {
      generateModes.value.push({
        label: 'React + TypeScript',
        codeGenType: 'vue',
        stream: true,
        stackProfileId: 'react-vite-ts',
      })
    }
  }).catch(() => {
    runtimeProfiles.value = []
  })
})

const columns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName', width: 240, ellipsis: true },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 110 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' },
]

const codeGenTypeText = (type?: string) =>
  type === 'multi_file' ? '多文件' : type === 'vue' ? 'Vue3 工程' : '单文件'

const codeGenTypeColor = (type?: string) =>
  type === 'multi_file' ? 'purple' : type === 'vue' ? 'green' : 'blue'
</script>

<template>
  <div class="home-page">
    <section class="hero">
      <span class="hero-badge">
        <ThunderboltFilled />
        AI 驱动 · 一句话生成应用
      </span>
      <h1 class="hero-title">把想法说出来，剩下的交给小玉</h1>
      <p class="hero-subtitle">描述你想要的网站应用，AI 自动生成可预览、可部署的完整代码</p>
    </section>

    <a-card :bordered="false" class="studio-card">
      <a-textarea
        v-model:value="prompt"
        class="studio-input"
        placeholder="描述你想创建的网站应用，例如：生成一个简洁美观的个人计划页面"
        :auto-size="{ minRows: 4, maxRows: 12 }"
        :maxlength="2000"
        show-count
        @keydown.ctrl.enter="handleCreate"
      />

      <div class="studio-examples">
        <span class="examples-label">没想好？试试：</span>
        <button
          v-for="ex in examplePrompts"
          :key="ex"
          type="button"
          class="example-chip"
          @click="prompt = ex"
        >
          {{ ex }}
        </button>
      </div>

      <div class="studio-actions">
        <div class="studio-options">
          <a-segmented v-model:value="createVisibility" :options="visibilityOptions" />
          <div class="studio-reasoning" title="档位越高，思考更充分，但耗时和 token 消耗也会增加">
            <span class="studio-reasoning-label">推理强度</span>
            <a-slider
              v-model:value="reasoningIndex"
              class="studio-reasoning-slider"
              :min="0"
              :max="3"
              :step="1"
              :marks="reasoningMarks"
            />
            <a-tag color="blue">{{ reasoningLabel }}</a-tag>
          </div>
          <a-select
            v-model:value="selectedModeIndex"
            :options="modeOptions"
            style="width: 190px"
          />
          <span class="shortcut-hint">Ctrl + Enter 快速创建</span>
        </div>
        <a-button type="primary" size="large" :loading="creating" @click="handleCreate">
          <template #icon><RocketOutlined /></template>
          创建应用
        </a-button>
      </div>
    </a-card>

    <a-tabs v-model:activeKey="activeTab" class="home-tabs" size="large" @change="handleTabChange">
      <!-- 我的应用 -->
      <a-tab-pane key="mine" tab="我的应用">
        <a-card :bordered="false">
          <div class="pane-toolbar">
            <div class="toolbar-filter">
              <a-input
                v-model:value="myAppKeyword"
                placeholder="搜索应用名称"
                allow-clear
                class="toolbar-input"
                @press-enter="myAppPagination.current = 1; fetchMyApps()"
              >
                <template #prefix><SearchOutlined /></template>
              </a-input>
              <a-button
                type="primary"
                @click="myAppPagination.current = 1; fetchMyApps()"
              >
                查询
              </a-button>
              <a-button @click="myAppKeyword = ''; myAppPagination.current = 1; fetchMyApps()">
                重置
              </a-button>
            </div>
            <a-button type="primary" @click="focusStudio">
              <template #icon><PlusOutlined /></template>
              新建应用
            </a-button>
          </div>

          <TableSkeleton v-if="myAppLoading && myAppList.length === 0" :rows="5" :cols="4" />
          <a-table
            v-else
            :columns="columns"
            :data-source="myAppList"
            :loading="myAppLoading"
            :pagination="{
              current: myAppPagination.current,
              pageSize: myAppPagination.pageSize,
              total: myAppPagination.total,
              showSizeChanger: false,
            }"
            row-key="id"
            :scroll="{ x: 700 }"
            @change="({ current }: any) => (myAppPagination.current = current, fetchMyApps())"
          >
            <template #emptyText>
              <EmptyState title="还没有应用" description="在上面描述你的想法，几秒钟就能生成第一个应用">
                <a-button type="primary" @click="focusStudio">
                  <template #icon><PlusOutlined /></template>
                  开始创建
                </a-button>
              </EmptyState>
            </template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'codeGenType'">
                <a-tag :color="codeGenTypeColor(record.codeGenType)">
                  {{ codeGenTypeText(record.codeGenType) }}
                </a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a-space :size="4">
                  <a-button type="link" size="small" @click="handleOpenApp(record.id)">
                    进入对话
                  </a-button>
                  <a-dropdown trigger="click">
                    <a-button type="text" size="small">
                      更多
                      <DownOutlined />
                    </a-button>
                    <template #overlay>
                      <a-menu @click="(info: any) => handleMoreClick(info, record)">
                        <a-menu-item key="edit">编辑信息</a-menu-item>
                        <a-menu-divider />
                        <a-menu-item key="delete" danger>删除到回收站</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>

      <!-- 精选应用 -->
      <a-tab-pane key="featured" tab="精选应用">
        <a-card :bordered="false">
          <div class="pane-toolbar">
            <div class="toolbar-filter">
              <a-input
                v-model:value="featuredKeyword"
                placeholder="搜索精选应用"
                allow-clear
                class="toolbar-input"
                @press-enter="featuredPagination.current = 1; fetchFeaturedApps()"
              >
                <template #prefix><SearchOutlined /></template>
              </a-input>
              <a-button
                type="primary"
                @click="featuredPagination.current = 1; fetchFeaturedApps()"
              >
                查询
              </a-button>
              <a-button
                @click="featuredKeyword = ''; featuredPagination.current = 1; fetchFeaturedApps()"
              >
                重置
              </a-button>
            </div>
          </div>

          <div v-if="featuredLoading && featuredList.length === 0" class="app-grid">
            <a-card v-for="n in 4" :key="n" :bordered="false" class="skeleton-card">
              <a-skeleton active :paragraph="{ rows: 2 }" />
            </a-card>
          </div>
          <EmptyState
            v-else-if="featuredList.length === 0"
            title="暂无精选应用"
            description="精选应用由管理员从公开应用中审核推荐"
          />
          <div v-else class="app-grid">
            <AppCard
              v-for="app in featuredList"
              :key="app.id"
              :app="app"
              @open="handleOpenApp"
            />
          </div>

          <div v-if="featuredPagination.total > featuredPagination.pageSize" class="pane-pagination">
            <a-pagination
              :current="featuredPagination.current"
              :page-size="featuredPagination.pageSize"
              :total="featuredPagination.total"
              :show-size-changer="false"
              @change="(page: number) => (featuredPagination.current = page, fetchFeaturedApps())"
            />
          </div>
        </a-card>
      </a-tab-pane>

      <!-- 公开应用 -->
      <a-tab-pane key="public" tab="公开应用">
        <a-card :bordered="false">
          <div class="pane-toolbar">
            <div class="toolbar-filter">
              <a-input
                v-model:value="publicKeyword"
                placeholder="搜索名称 / 描述 / 标签"
                allow-clear
                class="toolbar-input"
                @press-enter="handlePublicSearch"
              >
                <template #prefix><SearchOutlined /></template>
              </a-input>
              <a-button type="primary" @click="handlePublicSearch">查询</a-button>
              <a-button
                @click="publicKeyword = ''; publicTag = undefined; handlePublicSearch()"
              >
                重置
              </a-button>
            </div>
          </div>

          <div class="tag-filter">
            <button
              v-for="tag in hotTags"
              :key="tag"
              type="button"
              class="tag-chip"
              :class="{ active: publicTag === tag }"
              @click="handlePublicTagClick(tag)"
            >
              {{ tag }}
            </button>
          </div>

          <div v-if="publicLoading && publicList.length === 0" class="app-grid">
            <a-card v-for="n in 6" :key="n" :bordered="false" class="skeleton-card">
              <a-skeleton active :paragraph="{ rows: 2 }" />
            </a-card>
          </div>
          <EmptyState
            v-else-if="publicList.length === 0"
            title="暂无公开应用"
            description="换个关键词或标签试试，也可以把自己的应用设为公开"
          />
          <div v-else class="app-grid">
            <AppCard
              v-for="app in publicList"
              :key="app.id"
              :app="app"
              show-tags
              show-visibility
              @open="handleOpenApp"
            />
          </div>

          <div v-if="publicPagination.total > publicPagination.pageSize" class="pane-pagination">
            <a-pagination
              :current="publicPagination.current"
              :page-size="publicPagination.pageSize"
              :total="publicPagination.total"
              :show-size-changer="false"
              @change="(page: number) => (publicPagination.current = page, fetchPublicApps())"
            />
          </div>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="deleteModalOpen"
      title="删除应用"
      ok-text="删除到回收站"
      ok-type="danger"
      cancel-text="取消"
      :confirm-loading="deleting"
      @ok="confirmDelete"
    >
      <p>确定要把「{{ deleteTarget?.appName || '未命名应用' }}」删除到回收站吗？</p>
      <p class="modal-tip">删除后可以在「我的项目 → 回收站」里恢复。</p>
    </a-modal>
  </div>
</template>

<style scoped>
.home-page {
  max-width: 1080px;
  margin: 0 auto;
}

/* ---------- Hero ---------- */
.hero {
  padding: var(--space-6) 0 var(--space-5);
  text-align: center;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  box-shadow: var(--shadow-1);
  color: var(--brand-600);
  font-size: var(--font-sm);
  font-weight: 500;
}

.hero-title {
  margin-top: var(--space-4);
  background: var(--gradient-brand);
  background-clip: text;
  color: transparent;
  font-size: 42px;
  font-weight: 800;
  letter-spacing: 0.5px;
  -webkit-background-clip: text;
}

.hero-subtitle {
  margin-top: var(--space-3);
  color: var(--text-2);
  font-size: var(--font-lg);
}

/* ---------- 创作台 ---------- */
.studio-card {
  margin-bottom: var(--space-5);
}

.studio-input {
  border-radius: var(--radius-lg);
  font-size: 15px;
  line-height: 1.7;
}

.studio-examples {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-3);
}

.examples-label {
  color: var(--text-3);
  font-size: var(--font-sm);
}

.example-chip {
  padding: 4px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  color: var(--text-2);
  font-size: var(--font-sm);
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.example-chip:hover {
  border-color: var(--brand-300);
  background: var(--brand-50);
  color: var(--brand-600);
  transform: translateY(-1px);
}

.studio-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px dashed var(--border);
}

.studio-options {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.studio-reasoning {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 250px;
}

.studio-reasoning-label {
  color: var(--text-2);
  font-size: var(--font-sm);
  white-space: nowrap;
}

.studio-reasoning-slider {
  flex: 1;
  min-width: 150px;
  margin: 0;
}

.studio-reasoning-slider :deep(.ant-slider-mark-text) {
  font-size: 10px;
  white-space: nowrap;
}

.shortcut-hint {
  color: var(--text-3);
  font-size: var(--font-xs);
}

/* ---------- 列表区 ---------- */
.home-tabs :deep(.ant-tabs-nav) {
  margin-bottom: var(--space-4);
}

.home-tabs :deep(.ant-tabs-tab) {
  font-weight: 500;
}

.tag-filter {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-bottom: var(--space-4);
}

.tag-chip {
  padding: 3px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  background: var(--bg-surface);
  color: var(--text-2);
  font-size: var(--font-sm);
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.tag-chip:hover {
  border-color: var(--brand-300);
  color: var(--brand-600);
}

.tag-chip.active {
  border-color: transparent;
  background: var(--gradient-brand-soft);
  color: var(--brand-700);
  font-weight: 500;
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-4);
}

.skeleton-card {
  border-radius: var(--radius-xl);
}

.pane-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-5);
}

.modal-tip {
  margin-top: var(--space-2);
  color: var(--text-3);
  font-size: var(--font-sm);
}

@media (max-width: 768px) {
  .hero {
    padding: var(--space-4) 0 var(--space-3);
  }

  .hero-title {
    font-size: 28px;
  }

  .hero-subtitle {
    font-size: var(--font-base);
  }

  .studio-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .studio-reasoning {
    width: 100%;
  }

  .app-grid {
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: var(--space-3);
  }
}
</style>
