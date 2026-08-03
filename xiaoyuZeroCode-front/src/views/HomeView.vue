<script setup lang="ts">
</script>

<template>
  <div class="welcome-card">
    <a-card :bordered="false">
      <a-result
        status="success"
        title="小玉零代码应用生成平台"
        sub-title="快速构建你的应用，无需编写代码"
      >
        <template #extra>
          <a-space>
            <a-button type="primary" size="large">创建新项目</a-button>
            <a-button size="large">查看模板</a-button>
          </a-space>
        </template>
      </a-result>
    </a-card>
  </div>
</template>

<style scoped>
.welcome-card {
  max-width: 800px;
  margin: 80px auto 0;
}
</style>
