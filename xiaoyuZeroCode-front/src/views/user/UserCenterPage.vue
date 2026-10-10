<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { appMessage as message } from '@/utils/feedback'
import { SaveOutlined } from '@ant-design/icons-vue'
import PageContainer from '@/components/PageContainer.vue'
import { updateUser } from '@/api/userController'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const form = reactive<API.UserUpdateRequest>({
  id: userStore.loginUser?.id,
  userName: userStore.loginUser?.userName ?? '',
  userAvatar: userStore.loginUser?.userAvatar ?? '',
  userProfile: userStore.loginUser?.userProfile ?? '',
})

const avatarPreview = computed(() =>
  form.userAvatar ? form.userAvatar : undefined,
)
const avatarInitial = computed(() => form.userName?.charAt(0) || 'U')

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
  <PageContainer narrow title="个人中心" description="修改头像、昵称与个人简介">
    <a-card :bordered="false">
      <div class="profile-head">
        <a-avatar :src="avatarPreview" :size="64">
          {{ avatarInitial }}
        </a-avatar>
        <div class="profile-meta">
          <span class="profile-name">{{ form.userName || '未设置昵称' }}</span>
          <span class="profile-tip">头像支持填写图片链接</span>
        </div>
      </div>

      <a-form :model="form" layout="vertical">
        <a-form-item label="用户名">
          <a-input v-model:value="form.userName" placeholder="请输入用户名" />
        </a-form-item>
        <a-form-item label="头像链接">
          <a-input v-model:value="form.userAvatar" placeholder="请输入头像 URL" />
        </a-form-item>
        <a-form-item label="个人简介">
          <a-textarea
            v-model:value="form.userProfile"
            placeholder="介绍一下你自己，或写下常用需求"
            :rows="4"
            :maxlength="200"
            show-count
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" :loading="loading" @click="handleSave">
            <template #icon><SaveOutlined /></template>
            保存修改
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </PageContainer>
</template>

<style scoped>
.profile-head {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-5);
  border-bottom: 1px dashed var(--border);
}

.profile-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.profile-name {
  font-size: var(--font-lg);
  font-weight: 600;
}

.profile-tip {
  color: var(--text-3);
  font-size: var(--font-sm);
}
</style>
