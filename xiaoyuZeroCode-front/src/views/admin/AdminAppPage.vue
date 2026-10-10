<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { appMessage as message } from '@/utils/feedback'
import { DownOutlined, SearchOutlined } from '@ant-design/icons-vue'
import PageContainer from '@/components/PageContainer.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import {
  auditFeatured,
  cancelFeatured,
  deleteAppByAdmin,
  listAppByPage,
  updateAppByAdmin,
} from '@/api/appController'
import { featuredStatusText } from '@/utils/appStatus'

const router = useRouter()

interface SearchParams {
  appName: string
  codeGenType?: string
}

const searchForm = reactive<SearchParams>({
  appName: '',
  codeGenType: undefined,
})

const appList = ref<API.App[]>([])
const loading = ref(false)
const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
})

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listAppByPage({
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      appName: searchForm.appName || undefined,
      codeGenType: searchForm.codeGenType || undefined,
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
  searchForm.appName = ''
  searchForm.codeGenType = undefined
  handleSearch()
}

const handlePageChange = (page: number) => {
  pagination.current = page
  fetchData()
}

const handleEdit = (record: API.App) => {
  router.push(`/app/edit/${record.id}`)
}

/* 删除：不可逆操作走二次确认弹窗 */
const deleteModalOpen = ref(false)
const deleteTarget = ref<API.App | null>(null)
const deleting = ref(false)

const askDelete = (record: API.App) => {
  deleteTarget.value = record
  deleteModalOpen.value = true
}

const confirmDelete = async () => {
  const id = deleteTarget.value?.id
  if (!id) return
  deleting.value = true
  try {
    const res = await deleteAppByAdmin({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      deleteModalOpen.value = false
      fetchData()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch {
    message.error('删除失败')
  } finally {
    deleting.value = false
  }
}

// 精选审核：通过
const handleAuditPass = async (record: API.App) => {
  try {
    const res = await auditFeatured({ id: record.id, approved: true })
    if (res.data.code === 0) {
      message.success('已通过推荐')
      fetchData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败')
  }
}

// 精选审核：拒绝
const handleAuditReject = async (record: API.App) => {
  try {
    const res = await auditFeatured({ id: record.id, approved: false })
    if (res.data.code === 0) {
      message.success('已拒绝推荐')
      fetchData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败')
  }
}

// 取消推荐
const handleCancelFeatured = async (record: API.App) => {
  try {
    const res = await cancelFeatured({ id: record.id })
    if (res.data.code === 0) {
      message.success('已取消推荐')
      fetchData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败')
  }
}

// 置顶：priority 设为 999
const handlePin = async (record: API.App) => {
  try {
    const res = await updateAppByAdmin({ id: record.id, priority: 999 })
    if (res.data.code === 0) {
      message.success('已置顶')
      fetchData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败')
  }
}

// 取消置顶：priority 归 0
const handleUnpin = async (record: API.App) => {
  try {
    const res = await updateAppByAdmin({ id: record.id, priority: 0 })
    if (res.data.code === 0) {
      message.success('已取消置顶')
      fetchData()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败')
  }
}

/**
 * 行内「更多」菜单：把审核、置顶这些管理动作收起来。
 * 之前 6 个按钮平铺在操作列里，既挤又容易点错。
 */
const handleRowMore = ({ key }: { key: string | number }, record: API.App) => {
  const actions: Record<string, () => void> = {
    edit: () => handleEdit(record),
    pass: () => handleAuditPass(record),
    reject: () => handleAuditReject(record),
    cancelFeatured: () => handleCancelFeatured(record),
    pin: () => handlePin(record),
    unpin: () => handleUnpin(record),
    delete: () => askDelete(record),
  }
  actions[String(key)]?.()
}

onMounted(() => {
  fetchData()
})

const codeGenTypeText = (type?: string) =>
  type === 'multi_file' ? '多文件' : type === 'vue' ? 'Vue3 工程' : '单文件'

const codeGenTypeColor = (type?: string) =>
  type === 'multi_file' ? 'purple' : type === 'vue' ? 'green' : 'blue'

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 180 },
  { title: '应用名称', dataIndex: 'appName', key: 'appName', width: 200, ellipsis: true },
  { title: '类型', dataIndex: 'codeGenType', key: 'codeGenType', width: 90 },
  { title: '精选状态', dataIndex: 'featuredStatus', key: 'featuredStatus', width: 90 },
  { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80 },
  { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 180 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 150, fixed: 'right' },
]
</script>

<template>
  <PageContainer title="应用管理" description="审核精选、置顶与删除平台应用">
    <a-card :bordered="false">
      <div class="pane-toolbar">
        <div class="toolbar-filter">
          <a-input
            v-model:value="searchForm.appName"
            class="toolbar-input"
            placeholder="搜索应用名称"
            allow-clear
            @press-enter="handleSearch"
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
          <a-select
            v-model:value="searchForm.codeGenType"
            placeholder="应用类型"
            allow-clear
            style="width: 150px"
            @change="handleSearch"
          >
            <a-select-option value="html">单文件</a-select-option>
            <a-select-option value="multi_file">多文件</a-select-option>
            <a-select-option value="vue">Vue3 工程</a-select-option>
          </a-select>
          <a-button type="primary" @click="handleSearch">查询</a-button>
          <a-button @click="handleReset">重置</a-button>
        </div>
        <span v-if="!loading" class="toolbar-total">共 {{ pagination.total }} 个应用</span>
      </div>

      <TableSkeleton v-if="loading && appList.length === 0" :rows="6" :cols="8" />
      <a-table
        v-else
        :columns="columns"
        :data-source="appList"
        :loading="loading"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: false,
        }"
        row-key="id"
        :scroll="{ x: 1140 }"
        @change="({ current }: any) => handlePageChange(current)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'codeGenType'">
            <a-tag :color="codeGenTypeColor(record.codeGenType)">
              {{ codeGenTypeText(record.codeGenType) }}
            </a-tag>
          </template>
          <template v-if="column.key === 'featuredStatus'">
            <a-tag :color="featuredStatusText(record.featuredStatus).color">
              {{ featuredStatusText(record.featuredStatus).text }}
            </a-tag>
          </template>
          <template v-if="column.key === 'priority'">
            <a-tag v-if="record.priority === 999" color="gold">置顶</a-tag>
            <span v-else class="cell-muted">{{ record.priority ?? '—' }}</span>
          </template>
          <template v-if="column.key === 'action'">
            <a-space :size="4">
              <a-button type="link" size="small" @click="handleEdit(record)">编辑</a-button>
              <a-dropdown trigger="click">
                <a-button type="text" size="small">
                  更多
                  <DownOutlined />
                </a-button>
                <template #overlay>
                  <a-menu @click="(info: any) => handleRowMore(info, record)">
                    <a-menu-item v-if="record.featuredStatus === 1" key="pass">通过推荐</a-menu-item>
                    <a-menu-item v-if="record.featuredStatus === 1" key="reject" danger>
                      拒绝推荐
                    </a-menu-item>
                    <a-menu-item v-if="record.featuredStatus === 2" key="cancelFeatured">
                      取消精选
                    </a-menu-item>
                    <a-menu-item v-if="record.priority !== 999" key="pin">置顶</a-menu-item>
                    <a-menu-item v-if="record.priority === 999" key="unpin">取消置顶</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="delete" danger>删除应用</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:open="deleteModalOpen"
      title="删除应用"
      ok-text="删除"
      ok-type="danger"
      cancel-text="取消"
      :confirm-loading="deleting"
      @ok="confirmDelete"
    >
      <p>确定要删除「{{ deleteTarget?.appName || '未命名应用' }}」吗？</p>
      <p class="modal-tip">删除后该应用对用户不可见，请确认后再操作。</p>
    </a-modal>
  </PageContainer>
</template>

<style scoped>
.toolbar-total {
  color: var(--text-3);
  font-size: var(--font-sm);
}

.cell-muted {
  color: var(--text-3);
}

.modal-tip {
  margin-top: var(--space-2);
  color: var(--text-3);
  font-size: var(--font-sm);
}
</style>
