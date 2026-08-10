<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getAppVoById, updateApp, updateAppByAdmin } from '@/api/appController'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const appId = computed(() => route.params.id as string)
const isAdmin = computed(() => userStore.loginUser?.userRole === 'admin')

const loading = ref(true)
const noPermission = ref(false)
const saving = ref(false)

const form = reactive<API.AppUpdateRequest>({
  id: undefined,
  appName: '',
  cover: '',
  description: '',
  tags: '',
  visibility: 'public',
  priority: undefined,
})

const loadApp = async () => {
  loading.value = true
  try {
    const res = await getAppVoById({ id: appId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      const app = res.data.data
      // 普通用户只能编辑自己的应用
      if (
        !isAdmin.value &&
        app.userId !== userStore.loginUser?.id
      ) {
        noPermission.value = true
        return
      }
      form.id = app.id
      form.appName = app.appName ?? ''
      form.cover = app.cover ?? ''
      form.description = app.description ?? ''
      form.tags = app.tags ?? ''
      form.visibility = app.visibility ?? 'public'
      form.priority = app.priority
    } else {
      message.error(res.data.message || '加载应用失败')
    }
  } catch {
    message.error('加载应用失败')
  } finally {
    loading.value = false
  }
}

const handleSave = async () => {
  if (!form.id) return
  if (!form.appName?.trim()) {
    message.warning('请输入应用名称')
    return
  }
  saving.value = true
  try {
    const res = isAdmin.value
      ? await updateAppByAdmin({
          id: form.id,
          appName: form.appName,
          cover: form.cover || undefined,
          description: form.description || undefined,
          tags: form.tags || undefined,
          visibility: form.visibility,
          priority: form.priority,
        })
      : await updateApp({
          id: form.id,
          appName: form.appName,
          cover: form.cover || undefined,
          description: form.description || undefined,
          tags: form.tags || undefined,
          visibility: form.visibility,
        })
    if (res.data.code === 0) {
      message.success('保存成功')
      router.back()
    } else {
      message.error(res.data.message || '保存失败')
    }
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadApp()
})
</script>

<template>
  <div class="app-edit-page">
    <a-spin :spinning="loading">
      <a-result
        v-if="noPermission"
        status="403"
        title="无权编辑"
        sub-title="该应用不属于你，无法修改"
      >
        <template #extra>
          <a-button type="primary" @click="router.back()">返回</a-button>
        </template>
      </a-result>

      <a-card
        v-else-if="!loading"
        :title="`编辑应用 #${form.id}`"
        :bordered="false"
        class="edit-card"
      >
        <a-form :model="form" layout="vertical" style="max-width: 480px">
          <a-form-item label="应用名称">
            <a-input
              v-model:value="form.appName"
              placeholder="请输入应用名称"
              :maxlength="50"
            />
          </a-form-item>
          <a-form-item label="应用描述">
            <a-textarea
              v-model:value="form.description"
              placeholder="请输入应用描述"
              :rows="3"
              :maxlength="200"
            />
          </a-form-item>
          <a-form-item label="标签">
            <a-select
              v-model:value="form.tags"
              mode="tags"
              placeholder="输入标签后回车，多个标签用逗号分隔"
              :max-tag-count="6"
            />
          </a-form-item>
          <a-form-item label="可见范围">
            <a-radio-group v-model:value="form.visibility">
              <a-radio value="public">公开</a-radio>
              <a-radio value="private">私有</a-radio>
            </a-radio-group>
          </a-form-item>
          <template v-if="isAdmin">
            <a-form-item label="封面链接">
              <a-input v-model:value="form.cover" placeholder="请输入封面图片URL" />
            </a-form-item>
            <a-form-item label="优先级">
              <a-input-number
                v-model:value="form.priority"
                :min="0"
                :max="999"
                style="width: 100%"
              />
            </a-form-item>
          </template>
          <a-form-item>
            <a-space>
              <a-button type="primary" :loading="saving" @click="handleSave">
                保存
              </a-button>
              <a-button @click="router.back()">取消</a-button>
            </a-space>
          </a-form-item>
        </a-form>
      </a-card>
    </a-spin>
  </div>
</template>

<style scoped>
.app-edit-page {
  max-width: 700px;
  margin: 0 auto;
}

.edit-card {
  min-height: 300px;
}
</style>
