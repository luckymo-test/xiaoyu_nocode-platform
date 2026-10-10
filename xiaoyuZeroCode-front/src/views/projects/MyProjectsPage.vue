<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { appMessage as message } from '@/utils/feedback'
import {
  DownOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import PageContainer from '@/components/PageContainer.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
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

/* ---------------- 我的应用 ---------------- */
const myAppList = ref<API.AppVO[]>([])
const myAppLoading = ref(false)
const myAppPagination = reactive({ current: 1, pageSize: 10, total: 0 })
const myAppKeyword = ref('')
const myAppSelectedRowKeys = ref<number[]>([])

/* ---------------- 回收站 ---------------- */
const recycleList = ref<API.AppVO[]>([])
const recycleLoading = ref(false)
const recyclePagination = reactive({ current: 1, pageSize: 10, total: 0 })
const recycleKeyword = ref('')
const recycleSelectedRowKeys = ref<number[]>([])

/* ---------------- 新建弹窗 ---------------- */
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

/* ---------------- 删除：可逆/不可逆都走同一个确认弹窗 ---------------- */
const confirmModal = reactive({
  open: false,
  mode: 'recycle' as 'recycle' | 'permanent',
  apps: [] as API.AppVO[],
})
const confirmLoading = ref(false)

const askDelete = (apps: API.AppVO[], mode: 'recycle' | 'permanent') => {
  const valid = apps.filter((a) => a.id)
  if (valid.length === 0) return
  confirmModal.apps = valid
  confirmModal.mode = mode
  confirmModal.open = true
}

const confirmDelete = async () => {
  const ids = confirmModal.apps.map((a) => a.id as number)
  if (ids.length === 0) return
  const isPermanent = confirmModal.mode === 'permanent'
  confirmLoading.value = true
  try {
    let res
    if (ids.length === 1) {
      res = isPermanent ? await physicalDeleteApp({ id: ids[0] }) : await deleteApp({ id: ids[0] })
    } else {
      res = isPermanent
        ? await physicalDeleteAppBatch({ ids })
        : await deleteAppBatch({ ids })
    }
    if (res.data.code === 0) {
      message.success(
        isPermanent
          ? `已永久删除 ${ids.length} 个应用`
          : `已删除 ${ids.length} 个应用到回收站`,
      )
      confirmModal.open = false
      if (isPermanent) {
        recycleSelectedRowKeys.value = []
        fetchRecycledApps()
      } else {
        myAppSelectedRowKeys.value = []
        fetchMyApps()
      }
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败')
  } finally {
    confirmLoading.value = false
  }
}

/* 申请精选：仅在未申请(0)或已拒绝(3)时可申请 */
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

/* ---------------- 回收站操作 ---------------- */
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
  type === 'multi_file' ? '多文件' : type === 'vue' ? 'Vue3 工程' : '单文件'

const codeGenTypeColor = (type?: string) =>
  type === 'multi_file' ? 'purple' : type === 'vue' ? 'green' : 'blue'

const handleTabChange = (key: string) => {
  if (key === 'recycle') {
    fetchRecycledApps()
  }
}

/** 表格行内「更多」菜单：把次要操作与危险操作收起来，行内只留 1 个主入口 */
const handleRowMore = ({ key }: { key: string | number }, record: API.AppVO) => {
  if (key === 'edit') handleEdit(record.id)
  if (key === 'featured') handleApplyFeatured(record.id)
  if (key === 'delete') askDelete([record], 'recycle')
}

onMounted(() => {
  fetchMyApps()
})

const myAppColumns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName', width: 220, ellipsis: true },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 100 },
  { title: '可见性', dataIndex: 'visibility', key: 'visibility', width: 90 },
  { title: '生成状态', dataIndex: 'generateStatus', key: 'generateStatus', width: 100 },
  { title: '精选状态', dataIndex: 'featuredStatus', key: 'featuredStatus', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 190, fixed: 'right' },
]

const recycleColumns = [
  { title: '应用名称', dataIndex: 'appName', key: 'appName', width: 240, ellipsis: true },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 100 },
  { title: '删除时间', dataIndex: 'updateTime', key: 'updateTime', width: 180 },
  { title: '操作', key: 'action', width: 170, fixed: 'right' },
]
</script>

<template>
  <PageContainer title="我的项目" description="管理你创建的应用；删除的应用会先进回收站，可以恢复">
    <a-tabs size="large" @change="handleTabChange">
      <!-- 我的应用 -->
      <a-tab-pane key="my">
        <template #tab>
          我的应用
          <span v-if="myAppPagination.total > 0" class="tab-count">{{ myAppPagination.total }}</span>
        </template>

        <a-card :bordered="false">
          <div class="pane-toolbar">
            <div class="toolbar-filter">
              <a-input
                v-model:value="myAppKeyword"
                class="toolbar-input"
                placeholder="搜索应用名称"
                allow-clear
                @press-enter="handleMyAppSearch"
              >
                <template #prefix><SearchOutlined /></template>
              </a-input>
              <a-button type="primary" @click="handleMyAppSearch">查询</a-button>
              <a-button @click="myAppKeyword = ''; handleMyAppSearch()">重置</a-button>
            </div>

            <div class="toolbar-actions">
              <a-button
                danger
                :disabled="myAppSelectedRowKeys.length === 0"
                @click="
                  askDelete(
                    myAppList.filter((a) => myAppSelectedRowKeys.includes(a.id as number)),
                    'recycle',
                  )
                "
              >
                删除所选
                <template v-if="myAppSelectedRowKeys.length">（{{ myAppSelectedRowKeys.length }}）</template>
              </a-button>
              <a-button type="primary" @click="handleOpenAdd">
                <template #icon><PlusOutlined /></template>
                新建应用
              </a-button>
            </div>
          </div>

          <TableSkeleton
            v-if="myAppLoading && myAppList.length === 0"
            :rows="6"
            :cols="7"
          />
          <a-table
            v-else
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
            :scroll="{ x: 900 }"
            :row-selection="{
              selectedRowKeys: myAppSelectedRowKeys,
              onChange: (keys: any) => (myAppSelectedRowKeys = keys),
            }"
            @change="({ current }: any) => handleMyAppPageChange(current)"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'codeGenType'">
                <a-tag :color="codeGenTypeColor(record.codeGenType)">
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
                      <a-menu @click="(info: any) => handleRowMore(info, record)">
                        <a-menu-item key="edit">编辑信息</a-menu-item>
                        <a-menu-item
                          v-if="record.featuredStatus === 0 || record.featuredStatus === 3"
                          key="featured"
                        >
                          申请精选
                        </a-menu-item>
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

      <!-- 回收站 -->
      <a-tab-pane key="recycle">
        <template #tab>
          回收站
          <span v-if="recyclePagination.total > 0" class="tab-count">{{ recyclePagination.total }}</span>
        </template>

        <a-card :bordered="false">
          <div class="pane-toolbar">
            <div class="toolbar-filter">
              <a-input
                v-model:value="recycleKeyword"
                class="toolbar-input"
                placeholder="搜索应用名称"
                allow-clear
                @press-enter="handleRecycleSearch"
              >
                <template #prefix><SearchOutlined /></template>
              </a-input>
              <a-button type="primary" @click="handleRecycleSearch">查询</a-button>
              <a-button @click="recycleKeyword = ''; handleRecycleSearch()">重置</a-button>
            </div>

            <div class="toolbar-actions">
              <a-button
                :disabled="recycleSelectedRowKeys.length === 0"
                @click="handleBatchRecover"
              >
                恢复所选
                <template v-if="recycleSelectedRowKeys.length">（{{ recycleSelectedRowKeys.length }}）</template>
              </a-button>
              <a-button
                danger
                :disabled="recycleSelectedRowKeys.length === 0"
                @click="
                  askDelete(
                    recycleList.filter((a) => recycleSelectedRowKeys.includes(a.id as number)),
                    'permanent',
                  )
                "
              >
                永久删除
              </a-button>
            </div>
          </div>

          <TableSkeleton
            v-if="recycleLoading && recycleList.length === 0"
            :rows="4"
            :cols="4"
          />
          <a-table
            v-else
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
                <a-tag :color="codeGenTypeColor(record.codeGenType)">
                  {{ codeGenTypeText(record.codeGenType) }}
                </a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a-space :size="4">
                  <a-button type="link" size="small" @click="handleRecover(record.id)">
                    恢复
                  </a-button>
                  <a-button type="text" size="small" danger @click="askDelete([record], 'permanent')">
                    永久删除
                  </a-button>
                </a-space>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <!-- 新建应用 -->
    <a-modal
      v-model:open="addModalVisible"
      title="新建应用"
      ok-text="创建"
      cancel-text="取消"
      :confirm-loading="addLoading"
      @ok="handleAdd"
    >
      <a-form :model="addForm" layout="vertical">
        <a-form-item label="应用名称" required>
          <a-input v-model:value="addForm.appName" placeholder="请输入应用名称" :maxlength="50" />
        </a-form-item>
        <a-form-item label="初始提示词" required>
          <a-textarea
            v-model:value="addForm.initPrompt"
            placeholder="描述应用的用途，如：生成一个个人计划页面"
            :rows="3"
            :maxlength="300"
          />
        </a-form-item>
        <a-form-item label="生成类型">
          <a-select v-model:value="addForm.codeGenType">
            <a-select-option value="html">单文件</a-select-option>
            <a-select-option value="multi_file">多文件</a-select-option>
            <a-select-option value="vue">Vue3 工程</a-select-option>
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

    <!-- 删除确认：可逆与不可逆统一在这里说明后果 -->
    <a-modal
      v-model:open="confirmModal.open"
      :title="confirmModal.mode === 'permanent' ? '永久删除' : '删除到回收站'"
      :ok-text="confirmModal.mode === 'permanent' ? '永久删除' : '删除到回收站'"
      ok-type="danger"
      cancel-text="取消"
      :confirm-loading="confirmLoading"
      @ok="confirmDelete"
    >
      <p v-if="confirmModal.apps.length === 1">
        确定要把「{{ confirmModal.apps[0].appName || '未命名应用' }}」
        {{ confirmModal.mode === 'permanent' ? '永久删除' : '删除到回收站' }}吗？
      </p>
      <p v-else>
        确定要把选中的 {{ confirmModal.apps.length }} 个应用
        {{ confirmModal.mode === 'permanent' ? '永久删除' : '删除到回收站' }}吗？
      </p>
      <p class="modal-tip">
        {{
          confirmModal.mode === 'permanent'
            ? '永久删除后不可恢复，应用代码与对话记录都会一并清除。'
            : '删除后可以在「回收站」里恢复。'
        }}
      </p>
    </a-modal>
  </PageContainer>
</template>

<style scoped>
.modal-tip {
  margin-top: var(--space-2);
  color: var(--text-3);
  font-size: var(--font-sm);
}
</style>
