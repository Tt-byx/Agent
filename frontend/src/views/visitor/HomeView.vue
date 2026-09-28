<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="home-view">
    <div class="hero">
      <h1>欢迎来到景区AI导游</h1>
      <p>智能数字人为您提供专业的景区导览服务</p>
      <el-button type="primary" size="large" @click="router.push('/chat')">
        <el-icon><ChatDotRound /></el-icon>
        开始对话
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.home-view {
  display: flex;
  align-items: center;
  justify-content: center;
  height: calc(100vh - 60px);
}

.hero {
  text-align: center;
}

.hero h1 {
  font-size: 32px;
  color: #303133;
  margin-bottom: 12px;
}

.hero p {
  font-size: 16px;
  color: #909399;
  margin-bottom: 24px;
}
</style>
