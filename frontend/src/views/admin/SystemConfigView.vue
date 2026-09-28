<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'

const health = ref(null)
const loading = ref(false)

async function checkHealth() {
  loading.value = true
  try {
    const res = await request.get('/api/system/health')
    health.value = res || {}
  } catch (e) {
    health.value = { mysql: { status: 'error' }, python: { status: 'error', message: e.message } }
  } finally {
    loading.value = false
  }
}

function statusColor(s) {
  return s === 'ok' ? '#5a8a6a' : '#c0705a'
}

function statusLabel(s) {
  return s === 'ok' ? '正常' : '异常'
}

onMounted(checkHealth)
</script>

<template>
  <div class="system-config" v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统管理</h2>
        <p class="page-desc">查看系统各服务运行状态</p>
      </div>
      <el-button @click="checkHealth" :loading="loading" size="small">
        <el-icon :size="14"><Refresh /></el-icon>
        刷新状态
      </el-button>
    </div>

    <div class="status-grid" v-if="health">
      <div class="status-card" v-for="(info, key) in health" :key="key">
        <div class="status-icon" :style="{ background: statusColor(info.status) + '20', color: statusColor(info.status) }">
          <el-icon :size="24"><CircleCheckFilled v-if="info.status === 'ok'" /><WarningFilled v-else /></el-icon>
        </div>
        <div class="status-info">
          <span class="status-name">{{ key === 'mysql' ? 'MySQL 数据库' : 'Python 后端' }}</span>
          <span class="status-state" :style="{ color: statusColor(info.status) }">{{ statusLabel(info.status) }}</span>
          <span v-if="info.message" class="status-detail">{{ info.message }}</span>
          <span v-if="info.database" class="status-detail">数据库: {{ info.database }}</span>
        </div>
      </div>
    </div>

    <!-- 系统信息 -->
    <div class="panel">
      <div class="panel-head">系统信息</div>
      <div class="info-grid">
        <div class="info-item">
          <span class="info-label">后端框架</span>
          <span class="info-value">Spring Boot 3.3.5 + FastAPI</span>
        </div>
        <div class="info-item">
          <span class="info-label">前端框架</span>
          <span class="info-value">Vue 3 + Element Plus + ECharts</span>
        </div>
        <div class="info-item">
          <span class="info-label">大模型</span>
          <span class="info-value">MiMo v2.5 Pro</span>
        </div>
        <div class="info-item">
          <span class="info-label">向量数据库</span>
          <span class="info-value">ChromaDB（本地持久化）</span>
        </div>
        <div class="info-item">
          <span class="info-label">语音合成</span>
          <span class="info-value">edge-tts（Microsoft Edge 引擎）</span>
        </div>
        <div class="info-item">
          <span class="info-label">语音识别</span>
          <span class="info-value">MiMo mimo-v2.5-asr API</span>
        </div>
        <div class="info-item">
          <span class="info-label">数字人引擎</span>
          <span class="info-value">Live2D Cubism SDK v5</span>
        </div>
        <div class="info-item">
          <span class="info-label">Embedding</span>
          <span class="info-value">SiliconFlow BAAI/bge-m3</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.system-config {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.page-title { font-size: 20px; font-weight: 700; color: #2d3440; margin: 0; }
.page-desc { font-size: 13px; color: #8d95a3; margin: 4px 0 0; }

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.status-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #eae8e4;
  border-radius: 10px;
}

.status-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.status-info { display: flex; flex-direction: column; }
.status-name { font-size: 14px; font-weight: 600; color: #2d3440; }
.status-state { font-size: 13px; font-weight: 600; margin-top: 2px; }
.status-detail { font-size: 12px; color: #8d95a3; margin-top: 2px; }

.panel {
  background: #fff;
  border-radius: 10px;
  border: 1px solid #eae8e4;
  padding: 20px;
}

.panel-head {
  font-size: 14px;
  font-weight: 600;
  color: #2d3440;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eae8e4;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.info-item {
  display: flex;
  flex-direction: column;
  padding: 8px 0;
}

.info-label { font-size: 12px; color: #8d95a3; }
.info-value { font-size: 14px; color: #2d3440; font-weight: 500; margin-top: 2px; }

@media (max-width: 600px) {
  .info-grid { grid-template-columns: 1fr; }
}
</style>
