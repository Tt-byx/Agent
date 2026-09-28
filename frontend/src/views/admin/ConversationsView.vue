<script setup>
import { ref, onMounted } from 'vue'
import { getConversations } from '@/api/dashboard'

const conversations = ref([])

async function fetchConversations() {
  try {
    const data = await getConversations()
    conversations.value = data?.conversations || []
  } catch (e) {
    console.error('获取对话记录失败:', e)
  }
}

function formatTime(time) {
  if (!time) return '—'
  const d = new Date(time)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => fetchConversations())
</script>

<template>
  <div class="page">
    <div class="panel">
      <div class="panel-head">
        <span>对话记录</span>
        <span class="record-count" v-if="conversations.length">{{ conversations.length }} 条</span>
      </div>

      <el-table
        v-if="conversations.length"
        :data="conversations"
        stripe
        class="data-table"
        size="default"
      >
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="title" label="会话主题" min-width="150" show-overflow-tooltip />
        <el-table-column prop="first_user_message" label="首条消息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="last_ai_reply" label="最新回复" min-width="250" show-overflow-tooltip />
        <el-table-column prop="message_count" label="消息数" width="80" align="center" />
        <el-table-column label="时间" width="170" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ formatTime(row.session_time) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无对话记录" />
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1200px;
  margin: 0 auto;
}

.panel {
  background: var(--bg-card);
  border-radius: 10px;
  border: 1px solid var(--border-light);
  padding: 20px;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}

.record-count {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-tertiary);
  background: var(--bg-page);
  padding: 2px 8px;
  border-radius: 10px;
}

.data-table {
  --el-table-border-color: var(--border-light);
  --el-table-header-bg-color: var(--bg-page);
}

.time-cell {
  font-size: 13px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}
</style>
