<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { appMessage as message } from '@/utils/feedback'
import { SearchOutlined } from '@ant-design/icons-vue'
import PageContainer from '@/components/PageContainer.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { deleteUser, listUserVoByPage, updateUser } from '@/api/userController'

const searchForm = reactive({ userName: '' })

const userList = ref<API.UserVO[]>([])
const loading = ref(false)
const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
})

const editModalVisible = ref(false)
const editForm = reactive<API.UserUpdateRequest>({
  id: undefined,
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: '',
})
const editLoading = ref(false)

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listUserVoByPage({
      pageNum: pagination.current,
      pageSize: pagination.pageSize,
      userName: searchForm.userName || undefined,
    })
    if (res.data.code === 0 && res.data.data) {
      userList.value = res.data.data.records ?? []
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
  searchForm.userName = ''
  handleSearch()
}

const handlePageChange = (page: number) => {
  pagination.current = page
  fetchData()
}

const handleDelete = async (id: number) => {
  try {
    const res = await deleteUser({ id })
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

const handleEdit = (record: API.UserVO) => {
  editForm.id = record.id
  editForm.userName = record.userName ?? ''
  editForm.userAvatar = record.userAvatar ?? ''
  editForm.userProfile = record.userProfile ?? ''
  editForm.userRole = record.userRole ?? ''
  editModalVisible.value = true
}

const handleEditSubmit = async () => {
  if (!editForm.userName) {
    message.warning('请输入用户名')
    return
  }
  editLoading.value = true
  try {
    const res = await updateUser(editForm)
    if (res.data.code === 0) {
      message.success('更新成功')
      editModalVisible.value = false
      fetchData()
    } else {
      message.error(res.data.message || '更新失败')
    }
  } finally {
    editLoading.value = false
  }
}

onMounted(() => {
  fetchData()
})

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 200 },
  { title: '用户名', dataIndex: 'userName', key: 'userName', width: 180, ellipsis: true },
  { title: '账号', dataIndex: 'userAccount', key: 'userAccount', width: 180, ellipsis: true },
  { title: '头像', dataIndex: 'userAvatar', key: 'userAvatar', width: 80 },
  { title: '角色', dataIndex: 'userRole', key: 'userRole', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' },
]
</script>

<template>
  <PageContainer title="用户管理" description="查看、编辑与删除平台用户（仅管理员可见）">
    <a-card :bordered="false">
      <div class="pane-toolbar">
        <div class="toolbar-filter">
          <a-input
            v-model:value="searchForm.userName"
            class="toolbar-input"
            placeholder="搜索用户名"
            allow-clear
            @press-enter="handleSearch"
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
          <a-button type="primary" @click="handleSearch">查询</a-button>
          <a-button @click="handleReset">重置</a-button>
        </div>
        <span v-if="!loading" class="toolbar-total">共 {{ pagination.total }} 位用户</span>
      </div>

      <TableSkeleton v-if="loading && userList.length === 0" :rows="6" :cols="7" />
      <a-table
        v-else
        :columns="columns"
        :data-source="userList"
        :loading="loading"
        :pagination="{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: false,
        }"
        row-key="id"
        :scroll="{ x: 900 }"
        @change="({ current }: any) => handlePageChange(current)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'userAvatar'">
            <a-avatar :src="record.userAvatar" size="small">
              {{ record.userName?.charAt(0) || 'U' }}
            </a-avatar>
          </template>
          <template v-if="column.key === 'userRole'">
            <a-tag :color="record.userRole === 'admin' ? 'gold' : 'default'">
              {{ record.userRole === 'admin' ? '管理员' : '普通用户' }}
            </a-tag>
          </template>
          <template v-if="column.key === 'action'">
            <a-space :size="4">
              <a-button type="link" size="small" @click="handleEdit(record)">编辑</a-button>
              <a-popconfirm
                title="确定删除该用户？"
                description="删除后该用户将无法登录。"
                ok-text="删除"
                cancel-text="取消"
                @confirm="handleDelete(record.id)"
              >
                <a-button type="text" size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:open="editModalVisible"
      title="编辑用户信息"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleEditSubmit"
      :confirm-loading="editLoading"
    >
      <a-form :model="editForm" layout="vertical">
        <a-form-item label="用户名" required>
          <a-input v-model:value="editForm.userName" placeholder="请输入用户名" />
        </a-form-item>
        <a-form-item label="头像链接">
          <a-input v-model:value="editForm.userAvatar" placeholder="请输入头像 URL" />
        </a-form-item>
        <a-form-item label="个人简介">
          <a-textarea v-model:value="editForm.userProfile" :rows="3" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="editForm.userRole">
            <a-select-option value="user">普通用户</a-select-option>
            <a-select-option value="admin">管理员</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
  </PageContainer>
</template>

<style scoped>
.toolbar-total {
  color: var(--text-3);
  font-size: var(--font-sm);
}
</style>
