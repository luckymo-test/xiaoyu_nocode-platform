<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { appMessage as message } from '@/utils/feedback'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import AuthLayout from '@/components/AuthLayout.vue'
import { userLogin } from '@/api/userController'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const form = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const loading = ref(false)

const handleLogin = async () => {
  if (!form.userAccount || !form.userPassword) {
    message.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const res = await userLogin(form)
    if (res.data.code === 0 && res.data.data) {
      userStore.setLoginUser(res.data.data)
      message.success('登录成功')
      router.push('/')
    } else {
      message.error(res.data.message || '登录失败')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <h2 class="auth-title">欢迎回来</h2>
    <p class="auth-subtitle">登录后继续管理你的应用</p>

    <a-form class="auth-form-body" :model="form" layout="vertical" @finish="handleLogin">
      <a-form-item>
        <a-input
          v-model:value="form.userAccount"
          size="large"
          placeholder="请输入账号"
          autocomplete="username"
        >
          <template #prefix>
            <UserOutlined class="input-icon" />
          </template>
        </a-input>
      </a-form-item>
      <a-form-item>
        <a-input-password
          v-model:value="form.userPassword"
          size="large"
          placeholder="请输入密码"
          autocomplete="current-password"
        >
          <template #prefix>
            <LockOutlined class="input-icon" />
          </template>
        </a-input-password>
      </a-form-item>
      <a-form-item>
        <a-button type="primary" size="large" html-type="submit" :loading="loading" block>
          登录
        </a-button>
      </a-form-item>
    </a-form>

    <div class="auth-footer">
      还没有账号？
      <a-button type="link" @click="router.push('/user/register')">立即注册</a-button>
    </div>
  </AuthLayout>
</template>

<style scoped>
.input-icon {
  color: var(--text-3);
}
</style>
