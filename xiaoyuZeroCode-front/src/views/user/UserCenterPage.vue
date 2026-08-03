<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { updateUser } from '@/api/userController'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const form = reactive<API.UserUpdateRequest>({
  id: userStore.loginUser?.id,
  userName: userStore.loginUser?.userName ?? '',
  userAvatar: userStore.loginUser?.userAvatar ?? '',
  userProfile: userStore.loginUser?.userProfile ?? '',
})

const loading = ref(false)

const handleSave = async () => {
  if (!form.userName) {
    message.warning('请输入用户名')
    return
  }
  loading.value = true
  try {
    const res = await updateUser(form)
    if (res.data.code === 0) {
      message.success('更新成功')
      await userStore.fetchLoginUser()
    } else {
      message.error(res.data.message || '更新失败')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="center-container">
    <a-card title="个人中心" :bordered="false">
      <a-form :model="form" layout="vertical" style="max-width: 480px">
        <a-form-item label="用户名">
          <a-input v-model:value="form.userName" placeholder="请输入用户名" />
        </a-form-item>
        <a-form-item label="头像链接">
          <a-input v-model:value="form.userAvatar" placeholder="请输入头像URL" />
        </a-form-item>
        <a-form-item label="个人简介">
          <a-textarea
            v-model:value="form.userProfile"
            placeholder="请输入个人简介"
            :rows="4"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" :loading="loading" @click="handleSave">
            保存修改
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<style scoped>
.center-container {
  max-width: 600px;
  margin: 0 auto;
}
</style>
