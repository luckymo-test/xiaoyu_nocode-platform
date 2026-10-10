<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { appMessage as message } from '@/utils/feedback'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import AuthLayout from '@/components/AuthLayout.vue'
import { userRegister } from '@/api/userController'

const router = useRouter()

const form = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const loading = ref(false)

const handleRegister = async () => {
  if (!form.userAccount || !form.userPassword || !form.checkPassword) {
    message.warning('请填写完整信息')
    return
  }
  if (form.userPassword !== form.checkPassword) {
    message.warning('两次输入的密码不一致')
    return
  }
  if (form.userPassword.length < 8) {
    message.warning('密码长度不能少于8位')
    return
  }
  loading.value = true
  try {
    const res = await userRegister(form)
    if (res.data.code === 0) {
      message.success('注册成功，请登录')
      router.push('/user/login')
    } else {
      message.error(res.data.message || '注册失败')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <h2 class="auth-title">创建账号</h2>
    <p class="auth-subtitle">注册后即可创建并部署你的应用</p>

    <a-form class="auth-form-body" :model="form" layout="vertical" @finish="handleRegister">
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
          placeholder="请输入密码（不少于8位）"
          autocomplete="new-password"
        >
          <template #prefix>
            <LockOutlined class="input-icon" />
          </template>
        </a-input-password>
      </a-form-item>
      <a-form-item>
        <a-input-password
          v-model:value="form.checkPassword"
          size="large"
          placeholder="请确认密码"
          autocomplete="new-password"
        >
          <template #prefix>
            <LockOutlined class="input-icon" />
          </template>
        </a-input-password>
      </a-form-item>
      <a-form-item>
        <a-button type="primary" size="large" html-type="submit" :loading="loading" block>
          注册
        </a-button>
      </a-form-item>
    </a-form>

    <div class="auth-footer">
      已有账号？
      <a-button type="link" @click="router.push('/user/login')">立即登录</a-button>
    </div>
  </AuthLayout>
</template>

<style scoped>
.input-icon {
  color: var(--text-3);
}
</style>
