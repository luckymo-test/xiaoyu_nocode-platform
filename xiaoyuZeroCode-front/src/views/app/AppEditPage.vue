<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { appMessage as message } from '@/utils/feedback'
import { SaveOutlined } from '@ant-design/icons-vue'
import PageContainer from '@/components/PageContainer.vue'
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
      if (!isAdmin.value && app.userId !== userStore.loginUser?.id) {
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
  <PageContainer narrow title="编辑应用" description="修改应用名称、描述、标签与可见范围">
    <a-spin :spinning="loading">
      <a-card :bordered="false">
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

        <a-form v-else :model="form" layout="vertical">
          <a-form-item label="应用名称" required>
            <a-input
              v-model:value="form.appName"
              placeholder="请输入应用名称"
              :maxlength="50"
              show-count
            />
          </a-form-item>
          <a-form-item label="应用描述">
            <a-textarea
              v-model:value="form.description"
              placeholder="一句话介绍这个应用"
              :rows="3"
              :maxlength="200"
              show-count
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
            <a-divider orientation="left">管理员选项</a-divider>
            <a-form-item label="封面链接">
              <a-input v-model:value="form.cover" placeholder="请输入封面图片 URL" />
            </a-form-item>
            <a-form-item label="优先级">
              <a-input-number
                v-model:value="form.priority"
                :min="0"
                :max="999"
                style="width: 100%"
              />
              <span class="form-tip">设为 999 表示置顶</span>
            </a-form-item>
          </template>

          <div class="form-actions">
            <a-button @click="router.back()">取消</a-button>
            <a-button type="primary" :loading="saving" @click="handleSave">
              <template #icon><SaveOutlined /></template>
              保存
            </a-button>
          </div>
        </a-form>
      </a-card>
    </a-spin>
  </PageContainer>
</template>

<style scoped>
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px dashed var(--border);
}

.form-tip {
  margin-left: var(--space-2);
  color: var(--text-3);
  font-size: var(--font-xs);
}
</style>
