<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  addApp,
  deleteApp,
  listMyAppVoByPage,
  listFeaturedAppVoByPage,
  listPublicAppVoByPage,
} from '@/api/appController'
import { visibilityText } from '@/utils/appStatus'

const router = useRouter()

const prompt = ref('')
const creating = ref(false)

// 生成模式：single / multi / single-stream / multi-stream
interface GenerateMode {
  label: string
  codeGenType: string
  stream: boolean
}

const generateModes: GenerateMode[] = [
  { label: '单文件生成', codeGenType: 'html', stream: false },
  { label: '多文件生成', codeGenType: 'multi_file', stream: false },
  { label: '单文件流式生成', codeGenType: 'html', stream: true },
  { label: '多文件流式生成', codeGenType: 'multi_file', stream: true },
]

const selectedModeIndex = ref(2)
const selectedMode = computed(
  () => generateModes[selectedModeIndex.value],
)

// 创建时的可见范围（默认公开）
const createVisibility = ref<'public' | 'private'>('public')

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

// 公开应用（普通用户看别人公开应用的主入口）
const publicList = ref<API.AppVO[]>([])
const publicLoading = ref(false)
const publicPagination = reactive({ current: 1, pageSize: 12, total: 0 })
const publicKeyword = ref('')
const publicTag = ref<string | undefined>(undefined)

// 常用标签快捷筛选
const hotTags = ['个人', '电商', '时钟', '登录', '计划', '天气']

const handleCreate = async () => {
  const text = prompt.value.trim()
  if (!text) {
    message.warning('请输入应用描述')
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
      router.push(`/app/gen/${res.data.data}?stream=${selectedMode.value.stream}`)
    } else {
      message.error(res.data.message || '创建失败')
    }
  } catch {
    message.error('创建失败')
  } finally {
    creating.value = false
  }
}

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

const handleDelete = async (id?: number) => {
  if (!id) return
  try {
    const res = await deleteApp({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      fetchMyApps()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch {
    message.error('删除失败')
  }
}

onMounted(() => {
  fetchMyApps()
  fetchFeaturedApps()
  fetchPublicApps()
})

const columns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName' },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 120 },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
  },
  { title: '操作', key: 'action', width: 220, fixed: 'right' },
]
</script>

<template>
  <div class="home-page">
    <div class="hero">
      <h1 class="hero-title">小玉零代码</h1>
      <p class="hero-subtitle">输入你的需求，AI 为你生成网站应用</p>
    </div>

    <a-card :bordered="false" class="prompt-card">
      <a-textarea
        v-model:value="prompt"
        placeholder="描述你想创建的网站应用，例如：生成一个简洁美观的个人计划页面"
        :rows="3"
        @keydown.ctrl.enter="handleCreate"
      />
      <div class="prompt-actions">
        <a-space>
          <a-select
            v-model:value="createVisibility"
            style="width: 90px"
            :options="[
              { label: '公开', value: 'public' },
              { label: '私有', value: 'private' },
            ]"
          />
          <a-select
            v-model:value="selectedModeIndex"
            style="width: 180px"
            :options="generateModes.map((m, i) => ({ label: m.label, value: i }))"
          />
          <a-button
            type="primary"
            size="large"
            :loading="creating"
            @click="handleCreate"
          >
            创建应用
          </a-button>
        </a-space>
      </div>
    </a-card>

    <a-card
      :bordered="false"
      title="我的应用"
      class="list-card"
    >
      <div class="search-bar">
        <a-input
          v-model:value="myAppKeyword"
          placeholder="搜索应用名称"
          allow-clear
          style="width: 240px"
          @press-enter="myAppPagination.current = 1; fetchMyApps()"
        />
        <a-button
          type="primary"
          @click="myAppPagination.current = 1; fetchMyApps()"
        >
          搜索
        </a-button>
      </div>

      <a-table
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
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="handleOpenApp(record.id)">
                进入对话
              </a-button>
              <a-button type="link" size="small" @click="handleEdit(record.id)">
                编辑
              </a-button>
              <a-popconfirm title="确定删除该应用？" @confirm="handleDelete(record.id)">
                <a-button type="link" size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-card
      :bordered="false"
      title="精选应用"
      class="list-card"
    >
      <div class="search-bar">
        <a-input
          v-model:value="featuredKeyword"
          placeholder="搜索应用名称"
          allow-clear
          style="width: 240px"
          @press-enter="featuredPagination.current = 1; fetchFeaturedApps()"
        />
        <a-button
          type="primary"
          @click="featuredPagination.current = 1; fetchFeaturedApps()"
        >
          搜索
        </a-button>
      </div>

      <div v-if="featuredLoading" class="featured-empty">
        <a-spin />
      </div>
      <div v-else-if="featuredList.length === 0" class="featured-empty">
        <a-empty description="暂无精选应用" />
      </div>
      <div v-else class="featured-grid">
        <a-card
          v-for="app in featuredList"
          :key="app.id"
          hoverable
          class="featured-card"
          @click="handleOpenApp(app.id)"
        >
          <template #cover>
            <img
              v-if="app.cover"
              :src="app.cover"
              alt="cover"
              class="featured-cover"
            />
            <div v-else class="featured-cover featured-cover-placeholder">
              {{ app.appName?.charAt(0) || 'A' }}
            </div>
          </template>
          <a-card-meta
            :title="app.appName || '未命名应用'"
            :description="app.initPrompt || '暂无描述'"
          />
        </a-card>
      </div>

      <div class="featured-pagination">
        <a-pagination
          :current="featuredPagination.current"
          :page-size="featuredPagination.pageSize"
          :total="featuredPagination.total"
          :show-size-changer="false"
          @change="(page: number) => (featuredPagination.current = page, fetchFeaturedApps())"
        />
      </div>
    </a-card>

    <a-card
      :bordered="false"
      title="公开应用"
      class="list-card"
    >
      <div class="search-bar">
        <a-input
          v-model:value="publicKeyword"
          placeholder="搜索名称/描述/标签"
          allow-clear
          style="width: 240px"
          @press-enter="handlePublicSearch"
        />
        <a-button type="primary" @click="handlePublicSearch">搜索</a-button>
        <a-button @click="publicKeyword = ''; publicTag = undefined; handlePublicSearch()">重置</a-button>
      </div>

      <div class="tag-filter">
        <a-tag
          v-for="tag in hotTags"
          :key="tag"
          :color="publicTag === tag ? 'blue' : ''"
          class="tag-chip"
          @click="handlePublicTagClick(tag)"
        >
          {{ tag }}
        </a-tag>
      </div>

      <div v-if="publicLoading" class="featured-empty">
        <a-spin />
      </div>
      <div v-else-if="publicList.length === 0" class="featured-empty">
        <a-empty description="暂无公开应用" />
      </div>
      <div v-else class="featured-grid">
        <a-card
          v-for="app in publicList"
          :key="app.id"
          hoverable
          class="featured-card"
          @click="handleOpenApp(app.id)"
        >
          <template #cover>
            <img
              v-if="app.cover"
              :src="app.cover"
              alt="cover"
              class="featured-cover"
            />
            <div v-else class="featured-cover featured-cover-placeholder">
              {{ app.appName?.charAt(0) || 'A' }}
            </div>
          </template>
          <a-card-meta
            :title="app.appName || '未命名应用'"
            :description="app.description || app.initPrompt || '暂无描述'"
          >
            <template #description>
              <span>{{ app.description || app.initPrompt || '暂无描述' }}</span>
              <div class="public-tags">
                <a-tag
                  v-for="t in (app.tags ? app.tags.split(',') : []).slice(0, 3)"
                  :key="t"
                  color="blue"
                  style="margin-top: 4px"
                >
                  {{ t }}
                </a-tag>
                <a-tag
                  v-if="app.visibility"
                  :color="app.visibility === 'public' ? 'green' : 'default'"
                  style="margin-top: 4px"
                >
                  {{ visibilityText(app.visibility).text }}
                </a-tag>
              </div>
            </template>
          </a-card-meta>
        </a-card>
      </div>

      <div class="featured-pagination">
        <a-pagination
          :current="publicPagination.current"
          :page-size="publicPagination.pageSize"
          :total="publicPagination.total"
          :show-size-changer="false"
          @change="(page: number) => (publicPagination.current = page, fetchPublicApps())"
        />
      </div>
    </a-card>
  </div>
</template>

<style scoped>
.home-page {
  max-width: 1000px;
  margin: 0 auto;
}

.hero {
  text-align: center;
  margin: 24px 0 32px;
}

.hero-title {
  font-size: 40px;
  font-weight: bold;
  margin-bottom: 8px;
  background: linear-gradient(90deg, #1677ff, #722ed1);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero-subtitle {
  color: #666;
  font-size: 16px;
}

.prompt-card {
  margin-bottom: 24px;
}

.prompt-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.list-card {
  margin-bottom: 24px;
}

.search-bar {
  margin-bottom: 16px;
  display: flex;
  gap: 8px;
}

.featured-empty {
  padding: 32px 0;
  text-align: center;
}

.featured-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.featured-card {
  cursor: pointer;
}

.featured-cover {
  height: 140px;
  width: 100%;
  object-fit: cover;
}

.featured-cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40px;
  font-weight: bold;
  color: #fff;
  background: linear-gradient(135deg, #1677ff, #722ed1);
}

.featured-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.tag-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.tag-chip {
  cursor: pointer;
  user-select: none;
}

.public-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
