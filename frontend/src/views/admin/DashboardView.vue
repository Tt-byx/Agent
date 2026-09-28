<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { getOverview, getSentiment, getTrends, getConversations, getTopQuestions } from '@/api/dashboard'
import { getFeedbackStats } from '@/api/analytics'

const overview = ref({ todaySessions: 0, todayMessages: 0, totalSessions: 0, totalMessages: 0 })
const sentimentData = ref([])
const trendData = ref({ sessions: [], messages: [] })
const conversations = ref([])
const topQuestions = ref([])
const feedbackStats = ref({ likes: 0, dislikes: 0, total: 0, satisfactionRate: 0 })

let sentimentChart = null
let trendChart = null
let questionsChart = null

const sentimentColors = { positive: '#5a8a6a', neutral: '#a0aec0', negative: '#c0705a' }
const sentimentLabels = { positive: '正面', neutral: '中性', negative: '负面' }

const statCards = [
  { key: 'todaySessions', label: '今日会话', icon: 'ChatLineRound', accent: 'sage' },
  { key: 'todayMessages', label: '今日消息', icon: 'Comment', accent: 'warm' },
  { key: 'totalSessions', label: '总会话数', icon: 'Tickets', accent: 'stone' },
  { key: 'totalMessages', label: '总消息数', icon: 'Collection', accent: 'earth' },
]

async function fetchData() {
  try {
    const [ov, sent, trend, conv, top, fb] = await Promise.all([
      getOverview(), getSentiment(), getTrends(), getConversations(), getTopQuestions(),
      getFeedbackStats().catch(() => ({ likes: 0, dislikes: 0, total: 0, satisfactionRate: 0 })),
    ])
    overview.value = ov || overview.value
    sentimentData.value = sent?.distribution || []
    trendData.value = trend || trendData.value
    conversations.value = conv?.conversations || []
    topQuestions.value = top?.questions || []
    feedbackStats.value = fb || feedbackStats.value
    renderCharts()
  } catch (e) {
    console.error('获取数据大屏数据失败:', e)
  }
}

function renderCharts() {
  // Sentiment pie chart
  const sentEl = document.getElementById('sentiment-chart')
  if (sentEl) {
    if (!sentimentChart) sentimentChart = echarts.init(sentEl)
    sentimentChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, itemGap: 20, textStyle: { fontSize: 12, color: '#718096' } },
      series: [{
        type: 'pie', radius: ['45%', '72%'],
        center: ['50%', '45%'],
        label: { show: true, formatter: '{b}\n{d}%', fontSize: 11, color: '#4a5568' },
        data: sentimentData.value.map(d => ({
          name: sentimentLabels[d.sentiment] || d.sentiment,
          value: d.count,
          itemStyle: { color: sentimentColors[d.sentiment] || '#a0aec0' },
        })),
      }],
    })
  }

  // Trend line chart
  const trendEl = document.getElementById('trend-chart')
  if (trendEl) {
    if (!trendChart) trendChart = echarts.init(trendEl)
    const dates = trendData.value.sessions?.map(d => d.date) || []
    const sessionCounts = trendData.value.sessions?.map(d => d.count) || []
    const msgCounts = trendData.value.messages?.map(d => d.count) || []
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['会话数', '消息数'], bottom: 0, itemGap: 20, textStyle: { fontSize: 12, color: '#718096' } },
      grid: { top: 20, right: 20, bottom: 40, left: 50 },
      xAxis: { type: 'category', data: dates, axisLine: { lineStyle: { color: '#e2e8f0' } }, axisLabel: { color: '#718096', fontSize: 11 } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f0f0' } }, axisLabel: { color: '#718096', fontSize: 11 } },
      series: [
        { name: '会话数', type: 'line', smooth: true, data: sessionCounts, symbol: 'circle', symbolSize: 6, itemStyle: { color: '#5a8a6a' }, lineStyle: { width: 2.5 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(90,138,106,0.15)' }, { offset: 1, color: 'rgba(90,138,106,0.02)' }]) } },
        { name: '消息数', type: 'line', smooth: true, data: msgCounts, symbol: 'circle', symbolSize: 6, itemStyle: { color: '#c4956a' }, lineStyle: { width: 2.5 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(196,149,106,0.12)' }, { offset: 1, color: 'rgba(196,149,106,0.02)' }]) } },
      ],
    })
  }

  // Top questions bar chart
  const qEl = document.getElementById('questions-chart')
  if (qEl) {
    if (!questionsChart) questionsChart = echarts.init(qEl)
    const labels = topQuestions.value.map((_, i) => `Q${i + 1}`)
    const values = topQuestions.value.map(q => q.count)
    questionsChart.setOption({
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const idx = params[0].dataIndex
          const q = topQuestions.value[idx]
          return `${q?.content || ''}<br/>提问次数: ${q?.count || 0}`
        },
      },
      grid: { top: 16, right: 20, bottom: 40, left: 50 },
      xAxis: { type: 'category', data: labels, axisLine: { lineStyle: { color: '#e2e8f0' } }, axisLabel: { color: '#718096', fontSize: 11 } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f0f0' } }, axisLabel: { color: '#718096', fontSize: 11 } },
      series: [{
        type: 'bar', data: values, barWidth: '50%', barMaxWidth: 36,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#7ba88a' }, { offset: 1, color: '#5a8a6a' },
          ]),
          borderRadius: [4, 4, 0, 0],
        },
      }],
    })
  }
}

function formatTime(time) {
  if (!time) return '—'
  const d = new Date(time)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  fetchData()
  window.addEventListener('resize', () => {
    sentimentChart?.resize(); trendChart?.resize(); questionsChart?.resize()
  })
})
onUnmounted(() => {
  sentimentChart?.dispose(); trendChart?.dispose(); questionsChart?.dispose()
})
</script>

<template>
  <div class="dashboard">
    <!-- Stat cards -->
    <div class="stat-grid">
      <div
        v-for="card in statCards"
        :key="card.key"
        class="stat-card"
        :class="`stat-card--${card.accent}`"
      >
        <div class="stat-icon-wrap">
          <el-icon :size="20"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <span class="stat-value">{{ overview[card.key] }}</span>
          <span class="stat-label">{{ card.label }}</span>
        </div>
      </div>
    </div>

    <!-- Feedback stats -->
    <div class="panel feedback-panel" v-if="feedbackStats.total > 0">
      <div class="panel-head">用户反馈</div>
      <div class="feedback-grid">
        <div class="fb-item">
          <span class="fb-icon">👍</span>
          <span class="fb-count">{{ feedbackStats.likes }}</span>
          <span class="fb-label">点赞</span>
        </div>
        <div class="fb-item">
          <span class="fb-icon">👎</span>
          <span class="fb-count">{{ feedbackStats.dislikes }}</span>
          <span class="fb-label">点踩</span>
        </div>
        <div class="fb-item fb-satisfaction">
          <span class="fb-icon">😊</span>
          <span class="fb-count">{{ feedbackStats.satisfactionRate }}%</span>
          <span class="fb-label">满意度</span>
        </div>
      </div>
    </div>

    <!-- Charts row -->
    <div class="charts-grid">
      <div class="panel">
        <div class="panel-head">近 7 天趋势</div>
        <div id="trend-chart" class="chart-area"></div>
      </div>
      <div class="panel">
        <div class="panel-head">情感分布</div>
        <div id="sentiment-chart" class="chart-area"></div>
      </div>
    </div>

    <!-- Top questions -->
    <div class="panel">
      <div class="panel-head">热门问题 Top 10</div>
      <div id="questions-chart" class="chart-area"></div>
    </div>

    <!-- Recent conversations -->
    <div class="panel">
      <div class="panel-head">最近对话</div>
      <el-table :data="conversations" stripe class="data-table" size="default">
        <el-table-column prop="title" label="会话主题" min-width="150" show-overflow-tooltip />
        <el-table-column prop="first_user_message" label="首条消息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="last_ai_reply" label="最新回复" min-width="200" show-overflow-tooltip />
        <el-table-column prop="message_count" label="消息数" width="80" align="center" />
        <el-table-column label="时间" width="150" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ formatTime(row.session_time) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── Stat cards ── */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  border: 1px solid var(--border-light);
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.stat-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
  transform: translateY(-1px);
}

.stat-icon-wrap {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-card--sage .stat-icon-wrap { background: #e8f0eb; color: #5a8a6a; }
.stat-card--warm .stat-icon-wrap { background: #f5ede4; color: #c4956a; }
.stat-card--stone .stat-icon-wrap { background: #eae8e4; color: #8a8578; }
.stat-card--earth .stat-icon-wrap { background: #e4eae8; color: #6a858a; }

.stat-body {
  display: flex;
  flex-direction: column;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 13px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

/* ── Feedback ── */
.feedback-panel { margin-top: 0; }
.feedback-grid {
  display: flex;
  gap: 20px;
  justify-content: center;
}
.fb-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 32px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  min-width: 120px;
}
.fb-icon { font-size: 28px; margin-bottom: 6px; }
.fb-count { font-size: 24px; font-weight: 700; color: var(--text-primary); font-variant-numeric: tabular-nums; }
.fb-label { font-size: 12px; color: var(--text-tertiary); margin-top: 2px; }
.fb-satisfaction { border-color: #5a8a6a; background: #e8f0eb; }

/* ── Panels ── */
.panel {
  background: var(--bg-card);
  border-radius: 10px;
  border: 1px solid var(--border-light);
  padding: 20px;
}

.panel-head {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}

.chart-area {
  width: 100%;
  height: 280px;
}

/* ── Charts grid ── */
.charts-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
}

/* ── Data table ── */
.data-table {
  --el-table-border-color: var(--border-light);
  --el-table-header-bg-color: var(--bg-page);
}

.time-cell {
  font-size: 13px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .charts-grid { grid-template-columns: 1fr; }
}
</style>
