<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
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
  <div class="register-container">
    <a-card class="register-card" :bordered="false">
      <h2 class="register-title">小玉零代码</h2>
      <p class="register-subtitle">用户注册</p>
      <a-form :model="form" @finish="handleRegister">
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
            placeholder="请输入密码（不少于8位）"
          >
            <template #prefix>
              <LockOutlined style="color: rgba(0, 0, 0, 0.25)" />
            </template>
          </a-input-password>
        </a-form-item>
        <a-form-item>
          <a-input-password
            v-model:value="form.checkPassword"
            size="large"
            placeholder="请确认密码"
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
            注册
          </a-button>
        </a-form-item>
      </a-form>
      <div class="register-footer">
        已有账号？<a-button type="link" @click="router.push('/user/login')">
          立即登录
        </a-button>
      </div>
    </a-card>
  </div>
</template>

<style scoped>
.register-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 80vh;
}

.register-card {
  width: 400px;
  padding: 24px;
}

.register-title {
  text-align: center;
  font-size: 24px;
  margin-bottom: 4px;
}

.register-subtitle {
  text-align: center;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 24px;
}

.register-footer {
  text-align: center;
  color: rgba(0, 0, 0, 0.45);
}
</style>
