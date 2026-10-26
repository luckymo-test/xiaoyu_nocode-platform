<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AppstoreOutlined, ArrowRightOutlined } from '@ant-design/icons-vue'
import PageContainer from '@/components/PageContainer.vue'
import { appMessage as message } from '@/utils/feedback'
import {
  createProjectFromTemplate,
  listProjectTemplates,
  type ProjectTemplate,
} from '@/api/projectTemplates'

const router = useRouter()
const loading = ref(false)
const creatingId = ref('')
const templates = ref<ProjectTemplate[]>([])

const load = async () => {
  loading.value = true
  try {
    const res = await listProjectTemplates()
    if (res.data.code === 0) templates.value = res.data.data ?? []
  } finally {
    loading.value = false
  }
}

const useTemplate = async (template: ProjectTemplate) => {
  creatingId.value = template.id
  try {
    const res = await createProjectFromTemplate(template.id)
    if (res.data.code !== 0 || !res.data.data) {
      message.error(res.data.message || '创建项目失败')
      return
    }
    message.success('项目已创建，请确认需求与数据模型后开始生成')
    await router.push(`/app/gen/${res.data.data.appId}`)
  } catch {
    message.error('创建项目失败')
  } finally {
    creatingId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <PageContainer
    title="项目模板"
    description="从已经定义数据模型、权限和示例数据的业务起点开始，不必从空白提示词摸索。"
  >
    <a-spin :spinning="loading">
      <div class="template-grid">
        <article
          v-for="(template, index) in templates"
          :key="template.id"
          class="template-card"
        >
          <div class="template-index">0{{ index + 1 }}</div>
          <div class="template-icon"><AppstoreOutlined /></div>
          <h2>{{ template.displayName }}</h2>
          <p>{{ template.description }}</p>
          <div class="template-meta">
            <span>Vue 3 + Vite</span>
            <span>平台 Data/API</span>
            <span>含示例数据</span>
          </div>
          <a-button
            type="primary"
            :loading="creatingId === template.id"
            @click="useTemplate(template)"
          >
            使用模板
            <ArrowRightOutlined />
          </a-button>
        </article>
      </div>
    </a-spin>
  </PageContainer>
</template>

<style scoped>
.template-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-4);
}

.template-card {
  position: relative;
  min-height: 340px;
  padding: var(--space-6);
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background:
    radial-gradient(circle at 100% 0%, rgba(22, 119, 255, 0.1), transparent 38%),
    var(--bg-surface);
}

.template-index {
  position: absolute;
  top: 20px;
  right: 24px;
  color: var(--text-3);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0.16em;
}

.template-icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-container);
  font-size: 22px;
}

h2 {
  margin: var(--space-5) 0 var(--space-2);
  font-size: 24px;
}

p {
  min-height: 72px;
  margin: 0;
  color: var(--text-2);
  line-height: 1.75;
}

.template-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: var(--space-5) 0;
}

.template-meta span {
  padding: 4px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-3);
  font-size: 12px;
}

@media (max-width: 980px) {
  .template-grid {
    grid-template-columns: 1fr;
  }
}
</style>
