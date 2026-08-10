<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  addApp,
  applyFeatured,
  deleteApp,
  deleteAppBatch,
  listMyAppVoByPage,
  listRecycledAppVoByPage,
  physicalDeleteApp,
  physicalDeleteAppBatch,
  recoverApp,
  recoverAppBatch,
} from '@/api/appController'
import { featuredStatusText, generateStatusText, visibilityText } from '@/utils/appStatus'

const router = useRouter()

// 我的应用 tab
const myAppList = ref<API.AppVO[]>([])
const myAppLoading = ref(false)
const myAppPagination = reactive({ current: 1, pageSize: 10, total: 0 })
const myAppKeyword = ref('')
const myAppSelectedRowKeys = ref<number[]>([])

// 回收站 tab
const recycleList = ref<API.AppVO[]>([])
const recycleLoading = ref(false)
const recyclePagination = reactive({ current: 1, pageSize: 10, total: 0 })
const recycleKeyword = ref('')
const recycleSelectedRowKeys = ref<number[]>([])

// 新增弹窗
const addModalVisible = ref(false)
const addLoading = ref(false)
const addForm = reactive<API.AppAddRequest>({
  appName: '',
  initPrompt: '',
  codeGenType: 'html',
  visibility: 'public',
})

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

const fetchRecycledApps = async () => {
  recycleLoading.value = true
  try {
    const res = await listRecycledAppVoByPage({
      pageNum: recyclePagination.current,
      pageSize: recyclePagination.pageSize,
      appName: recycleKeyword.value.trim() || undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      recycleList.value = res.data.data.records ?? []
      recyclePagination.total = Number(res.data.data.totalRow ?? 0)
    }
  } finally {
    recycleLoading.value = false
  }
}

const handleOpenAdd = () => {
  addForm.appName = ''
  addForm.initPrompt = ''
  addForm.codeGenType = 'html'
  addForm.visibility = 'public'
  addModalVisible.value = true
}

const handleAdd = async () => {
  if (!addForm.appName?.trim()) {
    message.warning('请输入应用名称')
    return
  }
  if (!addForm.initPrompt?.trim()) {
    message.warning('请输入初始提示词')
    return
  }
  addLoading.value = true
  try {
    const res = await addApp({
      appName: addForm.appName,
      initPrompt: addForm.initPrompt,
      codeGenType: addForm.codeGenType,
    })
    if (res.data.code === 0) {
      message.success('创建成功')
      addModalVisible.value = false
      fetchMyApps()
    } else {
      message.error(res.data.message || '创建失败')
    }
  } catch {
    message.error('创建失败')
  } finally {
    addLoading.value = false
  }
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
    const res = await deleteApp({ id: id as unknown as number })
    if (res.data.code === 0) {
      message.success('已删除到回收站')
      fetchMyApps()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch {
    message.error('删除失败')
  }
}

const handleBatchDelete = async () => {
  if (myAppSelectedRowKeys.value.length === 0) {
    message.warning('请先选择要删除的应用')
    return
  }
  try {
    const res = await deleteAppBatch({ ids: myAppSelectedRowKeys.value })
    if (res.data.code === 0) {
      message.success(`已删除 ${myAppSelectedRowKeys.value.length} 个应用到回收站`)
      myAppSelectedRowKeys.value = []
      fetchMyApps()
    } else {
      message.error(res.data.message || '批量删除失败')
    }
  } catch {
    message.error('批量删除失败')
  }
}

// 申请精选：仅在未申请(0)或已拒绝(3)时可申请
const handleApplyFeatured = async (id?: number) => {
  if (!id) return
  try {
    const res = await applyFeatured({ id: id as unknown as number })
    if (res.data.code === 0) {
      message.success('已提交精选申请，等待审核')
      fetchMyApps()
    } else {
      message.error(res.data.message || '申请失败')
    }
  } catch {
    message.error('申请失败')
  }
}

const handleRecover = async (id?: number) => {
  if (!id) return
  try {
    const res = await recoverApp({ id: id as unknown as number })
    if (res.data.code === 0) {
      message.success('已恢复')
      fetchRecycledApps()
    } else {
      message.error(res.data.message || '恢复失败')
    }
  } catch {
    message.error('恢复失败')
  }
}

const handlePhysicalDelete = async (id?: number) => {
  if (!id) return
  try {
    const res = await physicalDeleteApp({ id: id as unknown as number })
    if (res.data.code === 0) {
      message.success('已永久删除')
      fetchRecycledApps()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch {
    message.error('删除失败')
  }
}

const handleBatchRecover = async () => {
  if (recycleSelectedRowKeys.value.length === 0) {
    message.warning('请先选择要恢复的应用')
    return
  }
  try {
    const res = await recoverAppBatch({ ids: recycleSelectedRowKeys.value })
    if (res.data.code === 0) {
      message.success(`已恢复 ${recycleSelectedRowKeys.value.length} 个应用`)
      recycleSelectedRowKeys.value = []
      fetchRecycledApps()
    } else {
      message.error(res.data.message || '批量恢复失败')
    }
  } catch {
    message.error('批量恢复失败')
  }
}

const handleBatchPhysicalDelete = async () => {
  if (recycleSelectedRowKeys.value.length === 0) {
    message.warning('请先选择要永久删除的应用')
    return
  }
  try {
    const res = await physicalDeleteAppBatch({ ids: recycleSelectedRowKeys.value })
    if (res.data.code === 0) {
      message.success(`已永久删除 ${recycleSelectedRowKeys.value.length} 个应用`)
      recycleSelectedRowKeys.value = []
      fetchRecycledApps()
    } else {
      message.error(res.data.message || '批量删除失败')
    }
  } catch {
    message.error('批量删除失败')
  }
}

const handleMyAppSearch = () => {
  myAppPagination.current = 1
  fetchMyApps()
}

const handleRecycleSearch = () => {
  recyclePagination.current = 1
  fetchRecycledApps()
}

const handleMyAppPageChange = (page: number) => {
  myAppPagination.current = page
  fetchMyApps()
}

const handleRecyclePageChange = (page: number) => {
  recyclePagination.current = page
  fetchRecycledApps()
}

const codeGenTypeText = (type?: string) =>
  type === 'multi_file' ? '多文件' : '单文件'

const handleTabChange = (key: string) => {
  if (key === 'recycle') {
    fetchRecycledApps()
  }
}

onMounted(() => {
  fetchMyApps()
})

const myAppColumns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName' },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 90 },
  {
    title: '可见性',
    dataIndex: 'visibility',
    key: 'visibility',
    width: 80,
  },
  {
    title: '生成状态',
    dataIndex: 'generateStatus',
    key: 'generateStatus',
    width: 90,
  },
  {
    title: '精选状态',
    dataIndex: 'featuredStatus',
    key: 'featuredStatus',
    width: 90,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 170,
  },
  { title: '操作', key: 'action', width: 260, fixed: 'right' },
]

const recycleColumns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName' },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 100 },
  {
    title: '删除时间',
    dataIndex: 'updateTime',
    key: 'updateTime',
    width: 180,
  },
  { title: '操作', key: 'action', width: 220, fixed: 'right' },
]
</script>

<template>
  <div class="my-projects-page">
    <a-tabs @change="handleTabChange">
      <a-tab-pane key="my" tab="我的应用">
        <a-card :bordered="false">
          <div class="toolbar">
            <div class="search-bar">
              <a-input
                v-model:value="myAppKeyword"
                placeholder="搜索应用名称"
                allow-clear
                style="width: 240px"
                @press-enter="handleMyAppSearch"
              />
              <a-button type="primary" @click="handleMyAppSearch">搜索</a-button>
              <a-button @click="myAppKeyword = ''; handleMyAppSearch()">重置</a-button>
            </div>
            <a-space>
              <a-button type="primary" @click="handleOpenAdd">新建应用</a-button>
              <a-popconfirm
                title="确定将选中的应用删除到回收站？"
                @confirm="handleBatchDelete"
              >
                <a-button
                  danger
                  :disabled="myAppSelectedRowKeys.length === 0"
                >
                  批量删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </div>

          <a-table
            :columns="myAppColumns"
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
            :row-selection="{
              selectedRowKeys: myAppSelectedRowKeys,
              onChange: (keys: any) => (myAppSelectedRowKeys = keys),
            }"
            @change="({ current }: any) => handleMyAppPageChange(current)"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'codeGenType'">
                <a-tag :color="record.codeGenType === 'multi_file' ? 'purple' : 'blue'">
                  {{ codeGenTypeText(record.codeGenType) }}
                </a-tag>
              </template>
              <template v-if="column.key === 'visibility'">
                <a-tag :color="visibilityText(record.visibility).color">
                  {{ visibilityText(record.visibility).text }}
                </a-tag>
              </template>
              <template v-if="column.key === 'generateStatus'">
                <a-tag :color="generateStatusText(record.generateStatus).color">
                  {{ generateStatusText(record.generateStatus).text }}
                </a-tag>
              </template>
              <template v-if="column.key === 'featuredStatus'">
                <a-tag :color="featuredStatusText(record.featuredStatus).color">
                  {{ featuredStatusText(record.featuredStatus).text }}
                </a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a-space>
                  <a-button type="link" size="small" @click="handleOpenApp(record.id)">
                    进入对话
                  </a-button>
                  <a-button type="link" size="small" @click="handleEdit(record.id)">
                    编辑
                  </a-button>
                  <a-button
                    v-if="record.featuredStatus === 0 || record.featuredStatus === 3"
                    type="link"
                    size="small"
                    @click="handleApplyFeatured(record.id)"
                  >
                    申请精选
                  </a-button>
                  <a-popconfirm
                    title="确定将该应用删除到回收站？"
                    @confirm="handleDelete(record.id)"
                  >
                    <a-button type="link" size="small" danger>删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="recycle" tab="回收站">
        <a-card :bordered="false">
          <div class="toolbar">
            <div class="search-bar">
              <a-input
                v-model:value="recycleKeyword"
                placeholder="搜索应用名称"
                allow-clear
                style="width: 240px"
                @press-enter="handleRecycleSearch"
              />
              <a-button type="primary" @click="handleRecycleSearch">搜索</a-button>
              <a-button @click="recycleKeyword = ''; handleRecycleSearch()">重置</a-button>
            </div>
            <a-space>
              <a-button
                type="primary"
                :disabled="recycleSelectedRowKeys.length === 0"
                @click="handleBatchRecover"
              >
                批量恢复
              </a-button>
              <a-popconfirm
                title="永久删除后不可恢复，确定？"
                @confirm="handleBatchPhysicalDelete"
              >
                <a-button
                  danger
                  :disabled="recycleSelectedRowKeys.length === 0"
                >
                  批量永久删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </div>

          <a-table
            :columns="recycleColumns"
            :data-source="recycleList"
            :loading="recycleLoading"
            :pagination="{
              current: recyclePagination.current,
              pageSize: recyclePagination.pageSize,
              total: recyclePagination.total,
              showSizeChanger: false,
            }"
            row-key="id"
            :scroll="{ x: 700 }"
            :row-selection="{
              selectedRowKeys: recycleSelectedRowKeys,
              onChange: (keys: any) => (recycleSelectedRowKeys = keys),
            }"
            @change="({ current }: any) => handleRecyclePageChange(current)"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'codeGenType'">
                <a-tag :color="record.codeGenType === 'multi_file' ? 'purple' : 'blue'">
                  {{ codeGenTypeText(record.codeGenType) }}
                </a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a-space>
                  <a-button
                    type="link"
                    size="small"
                    @click="handleRecover(record.id)"
                  >
                    恢复
                  </a-button>
                  <a-popconfirm
                    title="永久删除后不可恢复，确定？"
                    @confirm="handlePhysicalDelete(record.id)"
                  >
                    <a-button type="link" size="small" danger>永久删除</a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="addModalVisible"
      title="新建应用"
      @ok="handleAdd"
      :confirm-loading="addLoading"
    >
      <a-form :model="addForm" layout="vertical">
        <a-form-item label="应用名称">
          <a-input v-model:value="addForm.appName" placeholder="请输入应用名称" />
        </a-form-item>
        <a-form-item label="初始提示词">
          <a-textarea
            v-model:value="addForm.initPrompt"
            placeholder="描述应用的用途，如：生成一个个人计划页面"
            :rows="3"
          />
        </a-form-item>
        <a-form-item label="生成类型">
          <a-select v-model:value="addForm.codeGenType">
            <a-select-option value="html">单文件</a-select-option>
            <a-select-option value="multi_file">多文件</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="可见范围">
          <a-radio-group v-model:value="addForm.visibility">
            <a-radio value="public">公开</a-radio>
            <a-radio value="private">私有</a-radio>
          </a-radio-group>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.my-projects-page {
  max-width: 1100px;
  margin: 0 auto;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 8px;
}

.search-bar {
  display: flex;
  gap: 8px;
}
</style>
