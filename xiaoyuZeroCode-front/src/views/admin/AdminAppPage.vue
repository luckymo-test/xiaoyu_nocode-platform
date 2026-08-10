<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  listAppByPage,
  deleteAppByAdmin,
  updateAppByAdmin,
  auditFeatured,
  cancelFeatured,
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

const handleDelete = async (id?: number) => {
  if (!id) return
  try {
    const res = await deleteAppByAdmin({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      fetchData()
    } else {
      message.error(res.data.message || '删除失败')
    }
  } catch {
    message.error('删除失败')
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

onMounted(() => {
  fetchData()
})

const codeGenTypeText = (type?: string) =>
  type === 'multi_file' ? '多文件' : '单文件'

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 200 },
  { title: '应用名称', dataIndex: 'appName', key: 'appName' },
  {
    title: '类型',
    dataIndex: 'codeGenType',
    key: 'codeGenType',
    width: 100,
  },
  {
    title: '精选状态',
    dataIndex: 'featuredStatus',
    key: 'featuredStatus',
    width: 90,
  },
  { title: '优先级', dataIndex: 'priority', key: 'priority', width: 100 },
  { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 200 },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
  },
  { title: '操作', key: 'action', width: 300, fixed: 'right' },
]
</script>

<template>
  <div class="admin-app-page">
    <a-card :bordered="false">
      <div class="search-bar">
        <a-space>
          <a-input
            v-model:value="searchForm.appName"
            placeholder="搜索应用名称"
            allow-clear
            style="width: 240px"
            @press-enter="handleSearch"
          />
          <a-select
            v-model:value="searchForm.codeGenType"
            placeholder="应用类型"
            allow-clear
            style="width: 140px"
            @change="handleSearch"
          >
            <a-select-option value="html">单文件</a-select-option>
            <a-select-option value="multi_file">多文件</a-select-option>
          </a-select>
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
        row-key="id"
        :scroll="{ x: 1000 }"
        @change="({ current }: any) => handlePageChange(current)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'codeGenType'">
            <a-tag :color="record.codeGenType === 'multi_file' ? 'purple' : 'blue'">
              {{ codeGenTypeText(record.codeGenType) }}
            </a-tag>
          </template>
          <template v-if="column.key === 'featuredStatus'">
            <a-tag :color="featuredStatusText(record.featuredStatus).color">
              {{ featuredStatusText(record.featuredStatus).text }}
            </a-tag>
          </template>
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button
                type="link"
                size="small"
                @click="handleEdit(record)"
              >
                编辑
              </a-button>
              <template v-if="record.featuredStatus === 1">
                <a-popconfirm
                  title="确定通过该应用推荐？"
                  @confirm="handleAuditPass(record)"
                >
                  <a-button type="link" size="small">通过</a-button>
                </a-popconfirm>
                <a-popconfirm
                  title="确定拒绝该应用推荐？"
                  @confirm="handleAuditReject(record)"
                >
                  <a-button type="link" size="small" danger>拒绝</a-button>
                </a-popconfirm>
              </template>
              <a-popconfirm
                v-if="record.featuredStatus === 2"
                title="确定取消该应用推荐？"
                @confirm="handleCancelFeatured(record)"
              >
                <a-button type="link" size="small">取消精选</a-button>
              </a-popconfirm>
              <a-popconfirm
                v-if="record.priority !== 999"
                title="确定置顶该应用？"
                @confirm="handlePin(record)"
              >
                <a-button type="link" size="small">置顶</a-button>
              </a-popconfirm>
              <a-popconfirm
                v-if="record.priority === 999"
                title="确定取消置顶？"
                @confirm="handleUnpin(record)"
              >
                <a-button type="link" size="small">取消置顶</a-button>
              </a-popconfirm>
              <a-popconfirm
                title="确定删除该应用？"
                @confirm="handleDelete(record.id)"
              >
                <a-button type="link" size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<style scoped>
.admin-app-page {
  margin: 0 auto;
}

.search-bar {
  margin-bottom: 16px;
}
</style>
