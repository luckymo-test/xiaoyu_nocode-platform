<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import {
  PauseCircleOutlined,
  PlayCircleOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons-vue'
import { appMessage as message } from '@/utils/feedback'
import { userFacingGenerationError } from '@/utils/publicError'
import {
  confirmGenerationPlan,
  getGenerationJob,
  getGenerationPlan,
  getGenerationSteps,
  getGenerationTools,
  pauseGeneration,
  resumeGeneration,
  retryGeneration,
  retryGenerationStep,
  reviseGenerationPlan,
  type AgentPlan,
  type AgentStep,
  type GenerationJob,
  type AgentToolCall,
} from '@/api/workflow'

const props = defineProps<{
  jobId?: string
  refreshKey?: number
}>()

const emit = defineEmits<{
  changed: []
}>()

const loading = ref(false)
const action = ref('')
const plan = ref<AgentPlan | null>(null)
const steps = ref<AgentStep[]>([])
const toolCalls = ref<AgentToolCall[]>([])
const job = ref<GenerationJob | null>(null)
const planComment = ref('')

function parse(value?: string): Record<string, unknown> {
  if (!value) return {}
  try {
    return JSON.parse(value) as Record<string, unknown>
  } catch {
    return {}
  }
}

const requirement = computed(() => parse(plan.value?.requirementJson))
const architecture = computed(() => parse(plan.value?.architectureJson))
const ui = computed(() => parse(plan.value?.uiJson))
const waiting = computed(() => job.value?.status === 'WAITING_CONFIRMATION')
const paused = computed(() => job.value?.status === 'PAUSED')
const failed = computed(() => job.value?.status === 'FAILED')
const publicErrorMessage = computed(() => userFacingGenerationError(job.value?.errorMessage))
const publicErrorCode = computed(() =>
  /^[A-Z0-9_]+$/.test(job.value?.errorType ?? '')
    ? job.value?.errorType
    : 'GENERATION_FAILED',
)
const active = computed(() =>
  ['QUEUED', 'RUNNING', 'WAITING_RETRY', 'PAUSE_REQUESTED', 'PAUSED', 'WAITING_CONFIRMATION'].includes(
    job.value?.status ?? '',
  ),
)
const terminal = computed(() =>
  ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.value?.status ?? ''),
)

let pollTimer: number | null = null

const stopPolling = () => {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

const schedulePolling = () => {
  stopPolling()
  if (!props.jobId || terminal.value) return
  pollTimer = window.setTimeout(() => {
    void load()
  }, 3000)
}

const stringList = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []

const objectList = (value: unknown): Record<string, unknown>[] =>
  Array.isArray(value)
    ? value.filter(
        (item): item is Record<string, unknown> =>
          !!item && typeof item === 'object' && !Array.isArray(item),
      )
    : []

const load = async () => {
  if (!props.jobId) {
    plan.value = null
    steps.value = []
    toolCalls.value = []
    job.value = null
    stopPolling()
    return
  }
  loading.value = true
  try {
    try {
      const jobRes = await getGenerationJob(props.jobId)
      if (jobRes.data.code === 0) job.value = jobRes.data.data
    } catch {
      // Keep the last known Job; auxiliary endpoints must not hide its terminal state.
    }
    const [planResult, stepsResult, toolsResult] = await Promise.allSettled([
      getGenerationPlan(props.jobId),
      getGenerationSteps(props.jobId),
      getGenerationTools(props.jobId),
    ])
    if (planResult.status === 'fulfilled' && planResult.value.data.code === 0) {
      plan.value = planResult.value.data.data
    }
    if (stepsResult.status === 'fulfilled' && stepsResult.value.data.code === 0) {
      steps.value = stepsResult.value.data.data ?? []
    }
    if (toolsResult.status === 'fulfilled' && toolsResult.value.data.code === 0) {
      toolCalls.value = toolsResult.value.data.data ?? []
    }
  } catch {
    // The Job request itself failed; retain any previously loaded terminal state.
  } finally {
    loading.value = false
    schedulePolling()
  }
}

const confirm = async () => {
  if (!props.jobId || !plan.value) return
  action.value = 'confirm'
  try {
    const res = await confirmGenerationPlan(props.jobId, plan.value.planHash)
    if (res.data.code !== 0) {
      message.error(res.data.message || '确认失败')
      return
    }
    message.success('方案已确认，任务开始执行')
    await load()
    emit('changed')
  } finally {
    action.value = ''
  }
}

const revise = async () => {
  if (!props.jobId || !plan.value || !planComment.value.trim()) {
    message.warning('请输入需要补充或修改的内容')
    return
  }
  action.value = 'revise'
  try {
    const res = await reviseGenerationPlan(
      props.jobId,
      plan.value.planHash,
      planComment.value.trim(),
    )
    if (res.data.code !== 0) {
      message.error(res.data.message || '方案修改失败')
      return
    }
    planComment.value = ''
    message.success('方案已更新，请重新确认')
    await load()
    emit('changed')
  } finally {
    action.value = ''
  }
}

const pause = async () => {
  if (!props.jobId) return
  action.value = 'pause'
  try {
    await pauseGeneration(props.jobId)
    message.info('已请求暂停，将在安全边界生效')
    await load()
    emit('changed')
  } finally {
    action.value = ''
  }
}

const resume = async () => {
  if (!props.jobId) return
  action.value = 'resume'
  try {
    await resumeGeneration(props.jobId)
    message.success('任务已继续')
    await load()
    emit('changed')
  } finally {
    action.value = ''
  }
}

const retryStep = async (step: AgentStep) => {
  if (!props.jobId) return
  action.value = `retry-${step.stepKey}`
  try {
    await retryGenerationStep(props.jobId, step.stepKey)
    message.success('已从该步骤重试')
    await load()
    emit('changed')
  } finally {
    action.value = ''
  }
}

const retryJob = async () => {
  if (!props.jobId) return
  action.value = 'retry-job'
  try {
    const res = await retryGeneration(props.jobId)
    if (res.data.code !== 0) {
      message.error(res.data.message || '任务重试失败')
      return
    }
    message.success('已重新提交失败任务')
    await load()
    emit('changed')
  } finally {
    action.value = ''
  }
}

watch(() => [props.jobId, props.refreshKey], load, { immediate: true })
onUnmounted(stopPolling)
</script>

<template>
  <section v-if="jobId" class="workflow-panel">
    <div class="workflow-head">
      <div>
        <span class="workflow-kicker">AGENT WORKFLOW</span>
        <strong>{{ waiting ? '等待确认' : paused ? '已暂停' : failed ? '执行失败' : job?.status }}</strong>
      </div>
      <a-space>
        <a-button
          v-if="active && !waiting && !paused"
          size="small"
          :loading="action === 'pause'"
          @click="pause"
        >
          <PauseCircleOutlined /> 暂停
        </a-button>
        <a-button
          v-if="paused"
          size="small"
          type="primary"
          :loading="action === 'resume'"
          @click="resume"
        >
          <PlayCircleOutlined /> 继续
        </a-button>
      </a-space>
    </div>

    <div v-if="failed" class="job-error-card">
      <div>
        <strong>任务执行失败</strong>
        <span v-if="job?.currentStage">阶段：{{ job.currentStage }}</span>
      </div>
      <p>{{ publicErrorMessage }}</p>
      <div class="job-error-actions">
        <code>{{ publicErrorCode }}</code>
        <a-button
          type="primary"
          size="small"
          danger
          :loading="action === 'retry-job'"
          @click="retryJob"
        >
          重新执行任务
        </a-button>
      </div>
    </div>

    <div v-if="waiting" class="confirmation-card">
      <div class="confirmation-title">
        <SafetyCertificateOutlined />
        <span>确认后再写入代码</span>
      </div>
      <p>{{ requirement.objective || '需求分析已生成' }}</p>
      <div class="plan-columns">
        <div>
          <b>核心功能</b>
          <ul>
            <li v-for="item in stringList(requirement.mustHave).slice(0, 8)" :key="item">
              {{ item }}
            </li>
          </ul>
        </div>
        <div>
          <b>页面</b>
          <ul>
            <li v-for="page in objectList(architecture.pages).slice(0, 8)" :key="String(page.key)">
              {{ page.title || page.key }}
            </li>
          </ul>
        </div>
        <div>
          <b>数据模型</b>
          <ul>
            <li v-for="model in objectList(architecture.models).slice(0, 8)" :key="String(model.name)">
              {{ model.name }}
            </li>
          </ul>
        </div>
      </div>
      <p class="visual-note">视觉方向：{{ ui.visualDirection || '按项目模板与用户需求执行' }}</p>
      <div v-if="stringList(requirement.openQuestions).length" class="open-questions">
        <b>待确认</b>
        <span v-for="question in stringList(requirement.openQuestions)" :key="question">
          {{ question }}
        </span>
      </div>
      <div class="revise-box">
        <a-textarea
          v-model:value="planComment"
          :rows="2"
          :maxlength="1000"
          placeholder="需要调整功能、页面、数据模型或权限？在这里补充，系统会重新生成方案。"
        />
        <a-button size="small" :loading="action === 'revise'" @click="revise">
          修改方案
        </a-button>
      </div>
      <a-button
        type="primary"
        block
        :loading="action === 'confirm'"
        @click="confirm"
      >
        确认方案并开始生成
      </a-button>
    </div>

    <div v-if="steps.length" class="step-list">
      <div v-for="step in steps" :key="step.id" class="step-row">
        <span class="step-dot" :class="step.status.toLowerCase()"></span>
        <div>
          <strong>{{ step.title }}</strong>
          <span>{{ step.agentType }} · {{ step.status }}</span>
          <small v-if="step.errorMessage" class="step-error">
            {{ userFacingGenerationError(step.errorMessage) }}
          </small>
        </div>
        <a-button
          v-if="step.status === 'FAILED' || step.status === 'PAUSED'"
          size="small"
          :loading="action === `retry-${step.stepKey}`"
          @click="retryStep(step)"
        >
          重试
        </a-button>
      </div>
    </div>
    <details v-if="toolCalls.length" class="tool-log">
      <summary>工具调用与文件变更（{{ toolCalls.length }}）</summary>
      <div v-for="tool in toolCalls.slice(-20)" :key="tool.id" class="tool-row">
        <code>{{ tool.toolName }}</code>
        <span>{{ tool.filePath || '无文件' }}</span>
        <span>{{ tool.status }}</span>
        <span>{{ tool.durationMs ?? '-' }}ms</span>
      </div>
    </details>
  </section>
</template>

<style scoped>
.workflow-panel {
  margin: 0 var(--space-4) var(--space-3);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: color-mix(in srgb, var(--bg-surface) 88%, transparent);
}

.workflow-head,
.confirmation-title,
.step-row {
  display: flex;
  align-items: center;
}

.workflow-head {
  justify-content: space-between;
  gap: var(--space-3);
}

.workflow-head > div:first-child {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.workflow-kicker {
  color: var(--text-3);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 10px;
  letter-spacing: 0.15em;
}

.confirmation-card {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid color-mix(in srgb, var(--brand-500) 30%, var(--border));
  border-radius: var(--radius-md);
  background: var(--bg-container);
}

.job-error-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: var(--space-3);
  padding: var(--space-3);
  border: 1px solid color-mix(in srgb, var(--error) 45%, var(--border));
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--error) 7%, var(--bg-surface));
}

.job-error-card > div:first-child,
.job-error-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.job-error-card strong {
  color: var(--error);
}

.job-error-card span,
.job-error-card p {
  color: var(--text-2);
  font-size: 12px;
}

.job-error-card p {
  margin: 0;
  line-height: 1.6;
  word-break: break-word;
}

.job-error-actions code {
  color: var(--text-3);
  font-size: 11px;
}

.confirmation-title {
  gap: 8px;
  color: var(--brand-500);
  font-weight: 600;
}

.confirmation-card > p {
  margin: var(--space-3) 0;
  color: var(--text-2);
  line-height: 1.65;
}

.plan-columns {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}

.plan-columns b,
.open-questions b {
  font-size: 12px;
}

.plan-columns ul {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.8;
}

.visual-note {
  font-size: 12px;
}

.open-questions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: var(--space-3);
  padding: var(--space-3);
  border-left: 2px solid var(--warning);
  background: color-mix(in srgb, var(--warning) 7%, transparent);
  font-size: 12px;
}

.revise-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
  margin-bottom: var(--space-3);
}

.step-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: var(--space-3);
}

.tool-log {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px dashed var(--border);
}

.tool-log summary {
  cursor: pointer;
  color: var(--text-2);
  font-size: 12px;
}

.tool-row {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr) 70px 60px;
  gap: 8px;
  padding: 6px 0;
  color: var(--text-3);
  font-size: 11px;
}

.tool-row code {
  color: var(--text-1);
}

.step-row {
  gap: 10px;
}

.step-row > div {
  display: flex;
  flex: 1;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.step-row > div {
  flex-wrap: wrap;
}

.step-row .step-error {
  flex-basis: 100%;
  color: var(--error);
  font-size: 11px;
  line-height: 1.5;
}

.step-row span {
  color: var(--text-3);
}

.step-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-3);
}

.step-dot.running {
  background: var(--brand-500);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--brand-500) 15%, transparent);
}

.step-dot.succeeded {
  background: var(--success);
}

.step-dot.failed {
  background: var(--error);
}

@media (max-width: 760px) {
  .plan-columns {
    grid-template-columns: 1fr;
  }
}
</style>
