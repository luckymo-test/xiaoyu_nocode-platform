<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { listUserVoByPage, deleteUser, updateUser } from '@/api/userController'

interface SearchParams {
  userName: string
}

const searchForm = reactive<SearchParams>({
  userName: '',
})

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
      pagination.total = res.data.data.totalRow ?? 0
    }
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pagination.current = 1
  fetchData()
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
  { title: '用户名', dataIndex: 'userName', key: 'userName' },
  { title: '账号', dataIndex: 'userAccount', key: 'userAccount' },
  {
    title: '头像',
    dataIndex: 'userAvatar',
    key: 'userAvatar',
    width: 80,
  },
  { title: '角色', dataIndex: 'userRole', key: 'userRole', width: 80 },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
  },
  { title: '操作', key: 'action', width: 160, fixed: 'right' },
]
</script>

<template>
  <div class="admin-user-page">
    <a-card :bordered="false">
      <div class="search-bar">
        <a-space>
          <a-input
            v-model:value="searchForm.userName"
            placeholder="搜索用户名"
            allow-clear
            style="width: 240px"
            @press-enter="handleSearch"
          />
          <a-button type="primary" @click="handleSearch">搜索</a-button>
          <a-button @click="searchForm.userName = ''; handleSearch()">重置</a-button>
        </a-space>
      </div>

      <a-table
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
            <a-avatar :src="record.userAvatar" size="small" />
          </template>
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="handleEdit(record)">
                编辑
              </a-button>
              <a-popconfirm
                title="确定删除该用户？"
                @confirm="handleDelete(record.id)"
              >
                <a-button type="link" size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:open="editModalVisible"
      title="编辑用户信息"
      @ok="handleEditSubmit"
      :confirm-loading="editLoading"
    >
      <a-form :model="editForm" layout="vertical">
        <a-form-item label="用户名">
          <a-input v-model:value="editForm.userName" />
        </a-form-item>
        <a-form-item label="头像链接">
          <a-input v-model:value="editForm.userAvatar" />
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
  </div>
</template>

<style scoped>
.admin-user-page {
  margin: 0 auto;
}

.search-bar {
  margin-bottom: 16px;
}
</style>
