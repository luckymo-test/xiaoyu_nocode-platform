<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
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
  <div class="login-container">
    <a-card class="login-card" :bordered="false">
      <h2 class="login-title">小玉零代码</h2>
      <p class="login-subtitle">用户登录</p>
      <a-form :model="form" @finish="handleLogin">
        <a-form-item>
          <a-input
            v-model:value="form.userAccount"
            size="large"
            placeholder="请输入账号"
          >
            <template #prefix>
              <UserOutlined style="color: rgba(0, 0, 0, 0.25)" />
            </template>
          </a-input>
        </a-form-item>
        <a-form-item>
          <a-input-password
            v-model:value="form.userPassword"
            size="large"
            placeholder="请输入密码"
          >
            <template #prefix>
              <LockOutlined style="color: rgba(0, 0, 0, 0.25)" />
            </template>
          </a-input-password>
        </a-form-item>
        <a-form-item>
          <a-button
            type="primary"
            size="large"
            html-type="submit"
            :loading="loading"
            block
          >
            登录
          </a-button>
        </a-form-item>
      </a-form>
      <div class="login-footer">
        还没有账号？<a-button type="link" @click="router.push('/user/register')">
          立即注册
        </a-button>
      </div>
    </a-card>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 80vh;
}

.login-card {
  width: 400px;
  padding: 24px;
}

.login-title {
  text-align: center;
  font-size: 24px;
  margin-bottom: 4px;
}

.login-subtitle {
  text-align: center;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 24px;
}

.login-footer {
  text-align: center;
  color: rgba(0, 0, 0, 0.45);
}
</style>
