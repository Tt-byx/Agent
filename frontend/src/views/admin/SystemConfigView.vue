<script setup>
/**
 * ECharts 画在 Canvas 上，**不认 CSS 变量**。
 * 直接写 'var(--color-brand)' 会得到无效色值，退化成黑色（本次改版踩过这个坑）。
 * 所以这里把变量读成真实色值再交给图表。
 */
function cssVar(name) {
  if (typeof document === 'undefined') return ''
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

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
    health.value = {
      mysql: { status: 'error', message: e.message },
      redis: { status: 'error', message: e.message },
    }
  } finally {
    loading.value = false
  }
}

/** 服务名映射：health 返回的 key → 展示名 */
const SERVICE_NAMES = {
  mysql: 'MySQL 业务库',
  redis: 'Redis 缓存',
  pgvector: 'PostgreSQL 向量库',
}

function serviceName(key) {
  return SERVICE_NAMES[key] || key
}

function statusColor(s) {
  return s === 'ok' ? cssVar('--color-brand') : cssVar('--color-danger')
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
          <span class="status-name">{{ serviceName(key) }}</span>
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
          <span class="info-value">Spring Boot 3.3.5 + LangChain4j（单体）</span>
        </div>
        <div class="info-item">
          <span class="info-label">前端框架</span>
          <span class="info-value">Vue 3 + Element Plus + ECharts</span>
        </div>
        <div class="info-item">
          <span class="info-label">对话模型</span>
          <span class="info-value">DeepSeek（deepseek-flash，OpenAI 兼容）</span>
        </div>
        <div class="info-item">
          <span class="info-label">向量数据库</span>
          <span class="info-value">PostgreSQL + pgvector</span>
        </div>
        <div class="info-item">
          <span class="info-label">Embedding</span>
          <span class="info-value">SiliconFlow BAAI/bge-m3</span>
        </div>
        <div class="info-item">
          <span class="info-label">重排序</span>
          <span class="info-value">SiliconFlow BAAI/bge-reranker-v2-m3</span>
        </div>
        <div class="info-item">
          <span class="info-label">缓存 / 记忆</span>
          <span class="info-value">Redis（短期记忆 / 限流 / 幂等 / 语义缓存）</span>
        </div>
        <div class="info-item">
          <span class="info-label">部署方式</span>
          <span class="info-value">Docker Compose（全容器化）</span>
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

.page-title { font-size: 20px; font-weight: 700; color: var(--color-ink); margin: 0; }
.page-desc { font-size: 13px; color: var(--color-muted); margin: 4px 0 0; }

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
  background: var(--color-surface);
  border: 1px solid var(--color-line);
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
.status-name { font-size: 14px; font-weight: 600; color: var(--color-ink); }
.status-state { font-size: 13px; font-weight: 600; margin-top: 2px; }
.status-detail { font-size: 12px; color: var(--color-muted); margin-top: 2px; }

.panel {
  background: var(--color-surface);
  border-radius: 10px;
  border: 1px solid var(--color-line);
  padding: 20px;
}

.panel-head {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-line);
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

.info-label { font-size: 12px; color: var(--color-muted); }
.info-value { font-size: 14px; color: var(--color-ink); font-weight: 500; margin-top: 2px; }

@media (max-width: 600px) {
  .info-grid { grid-template-columns: 1fr; }
}
</style>
