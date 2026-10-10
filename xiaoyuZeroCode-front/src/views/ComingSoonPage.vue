<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import PageContainer from '@/components/PageContainer.vue'

const route = useRoute()
const router = useRouter()

const title = (route.meta.title as string) || '敬请期待'
</script>

<template>
  <PageContainer :title="title" description="该功能正在规划中，先去看看已经生成的成果吧">
    <a-card :bordered="false">
      <a-result
        status="info"
        :title="`${title} · 即将上线`"
        sub-title="模板市场与文档中心正在建设中，上线后会在这里提供可直接套用的模板与使用指南。"
      >
        <template #extra>
          <a-button type="primary" @click="router.push('/')">回到首页</a-button>
          <a-button @click="router.push('/projects')">我的项目</a-button>
        </template>
      </a-result>
    </a-card>
  </PageContainer>
</template>
