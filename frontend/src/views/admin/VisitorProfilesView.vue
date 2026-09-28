<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import * as echarts from 'echarts'
import { getVisitorProfiles } from '@/api/analytics'

const loading = ref(false)
const visitors = ref([])
const expandedUserId = ref(null)

let radarChart = null
let sentimentChart = null

const palette = {
  sage: '#5a8a6a',
  sageLight: '#7ba88a',
  warm: '#c4956a',
  stone: '#8a8578',
  teal: '#6a858a',
  rose: '#c0705a',
  lavender: '#8b7bb5',
}

const tagColors = [palette.sage, palette.warm, palette.teal, palette.lavender, palette.rose]

async function loadData() {
  loading.value = true
  try {
    const data = await getVisitorProfiles()
    visitors.value = Array.isArray(data) ? data : []
  } catch {
    visitors.value = []
  } finally {
    loading.value = false
  }
}

function toggleExpand(userId) {
  if (expandedUserId.value === userId) {
    expandedUserId.value = null
    return
  }
  expandedUserId.value = userId
  nextTick(() => renderCharts(userId))
}

function getVisitorById(userId) {
  return visitors.value.find(v => v.userId === userId)
}

function getTopTag(visitor) {
  if (!visitor.tags || visitor.tags.length === 0) return '暂无'
  return visitor.tags[0].name
}

function getTotalSentiment(visitor) {
  const s = visitor.sentiment
  return (s?.positive || 0) + (s?.neutral || 0) + (s?.negative || 0)
}

function getSentimentLabel(visitor) {
  const s = visitor.sentiment
  const total = getTotalSentiment(visitor)
  if (total === 0) return '暂无数据'
  const posRatio = (s?.positive || 0) / total
  if (posRatio >= 0.6) return '😊 积极'
  const negRatio = (s?.negative || 0) / total
  if (negRatio >= 0.3) return '😟 消极'
  return '😐 中性'
}

function renderCharts(userId) {
  const visitor = getVisitorById(userId)
  if (!visitor) return

  // Radar chart for tags
  const radarEl = document.getElementById(`radar-${userId}`)
  if (radarEl) {
    if (radarChart) radarChart.dispose()
    radarChart = echarts.init(radarEl)
    const allTagNames = ['历史文化', '自然风光', '亲子互动', '摄影打卡', '美食禅意']
    const tagMap = {}
    for (const t of visitor.tags || []) tagMap[t.name] = t.score
    const maxScore = Math.max(...Object.values(tagMap), 1)
    radarChart.setOption({
      radar: {
        indicator: allTagNames.map(name => ({ name, max: maxScore * 1.2 })),
        shape: 'polygon',
        splitNumber: 3,
        axisName: { color: '#666', fontSize: 12 },
      },
      series: [{
        type: 'radar',
        data: [{
          value: allTagNames.map(name => tagMap[name] || 0),
          name: '兴趣标签',
          areaStyle: { color: 'rgba(90, 138, 106, 0.2)' },
          lineStyle: { color: palette.sage, width: 2 },
          itemStyle: { color: palette.sage },
        }],
      }],
    })
  }

  // Sentiment pie chart
  const pieEl = document.getElementById(`sentiment-${userId}`)
  if (pieEl) {
    if (sentimentChart) sentimentChart.dispose()
    sentimentChart = echarts.init(pieEl)
    const s = visitor.sentiment || {}
    sentimentChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, textStyle: { fontSize: 12, color: '#666' } },
      series: [{
        type: 'pie',
        radius: ['40%', '65%'],
        center: ['50%', '45%'],
        label: { show: false },
        data: [
          { value: s.positive || 0, name: '积极', itemStyle: { color: palette.sage } },
          { value: s.neutral || 0, name: '中性', itemStyle: { color: palette.stone } },
          { value: s.negative || 0, name: '消极', itemStyle: { color: palette.rose } },
        ].filter(d => d.value > 0),
      }],
    })
  }
}

function handleResize() {
  if (radarChart) radarChart.resize()
  if (sentimentChart) sentimentChart.resize()
}

onMounted(() => {
  loadData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  if (radarChart) radarChart.dispose()
  if (sentimentChart) sentimentChart.dispose()
})
</script>

<template>
  <div class="visitor-profiles">
    <div class="page-header">
      <h2 class="page-title">👥 游客画像</h2>
      <p class="page-desc">基于对话内容自动提取的游客兴趣标签与情感分析</p>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>

    <div v-else-if="visitors.length === 0" class="empty-state">
      <el-empty description="暂无游客数据" />
    </div>

    <div v-else class="visitors-list">
      <div
        v-for="visitor in visitors"
        :key="visitor.userId"
        class="visitor-card"
        :class="{ expanded: expandedUserId === visitor.userId }"
      >
        <div class="visitor-header" @click="toggleExpand(visitor.userId)">
          <div class="visitor-info">
            <div class="visitor-avatar">
              {{ (visitor.nickname || '游')[0] }}
            </div>
            <div class="visitor-basic">
              <div class="visitor-name">{{ visitor.nickname || '游客' }}</div>
              <div class="visitor-meta">
                对话 {{ visitor.conversationCount || 0 }} 次 · {{ getSentimentLabel(visitor) }}
              </div>
            </div>
          </div>
          <div class="visitor-tags-inline">
            <el-tag
              v-for="(tag, i) in (visitor.tags || []).slice(0, 3)"
              :key="tag.name"
              :color="tagColors[i % tagColors.length]"
              effect="dark"
              size="small"
              round
              style="border: none; color: #fff;"
            >
              {{ tag.name }}
            </el-tag>
            <el-tag v-if="!visitor.tags || visitor.tags.length === 0" type="info" size="small">
              暂无标签
            </el-tag>
          </div>
          <el-icon class="expand-icon" :class="{ rotated: expandedUserId === visitor.userId }">
            <ArrowDown />
          </el-icon>
        </div>

        <transition name="slide">
          <div v-if="expandedUserId === visitor.userId" class="visitor-detail">
            <div class="detail-grid">
              <div class="detail-section">
                <h4 class="section-title">🎯 兴趣标签</h4>
                <div :id="`radar-${visitor.userId}`" class="chart-container"></div>
                <div class="tag-list">
                  <div v-for="(tag, i) in (visitor.tags || [])" :key="tag.name" class="tag-row">
                    <span class="tag-dot" :style="{ background: tagColors[i % tagColors.length] }"></span>
                    <span class="tag-name">{{ tag.name }}</span>
                    <span class="tag-score">{{ tag.score }} 次</span>
                  </div>
                  <div v-if="!visitor.tags || visitor.tags.length === 0" class="no-data">暂无标签数据</div>
                </div>
              </div>
              <div class="detail-section">
                <h4 class="section-title">😊 情感分布</h4>
                <div :id="`sentiment-${visitor.userId}`" class="chart-container"></div>
                <div class="sentiment-stats">
                  <div class="stat-item">
                    <span class="stat-dot" style="background: #5a8a6a;"></span>
                    <span>积极 {{ visitor.sentiment?.positive || 0 }}</span>
                  </div>
                  <div class="stat-item">
                    <span class="stat-dot" style="background: #8a8578;"></span>
                    <span>中性 {{ visitor.sentiment?.neutral || 0 }}</span>
                  </div>
                  <div class="stat-item">
                    <span class="stat-dot" style="background: #c0705a;"></span>
                    <span>消极 {{ visitor.sentiment?.negative || 0 }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </transition>
      </div>
    </div>
  </div>
</template>

<style scoped>
.visitor-profiles { max-width: 1200px; }
.page-header { margin-bottom: 24px; }
.page-title { font-size: 20px; font-weight: 700; color: #2d3440; margin: 0 0 6px; }
.page-desc { font-size: 13px; color: #8d95a3; margin: 0; }
.loading-state { text-align: center; color: #999; padding: 60px 0; }
.empty-state { padding: 60px 0; }

.visitors-list { display: flex; flex-direction: column; gap: 12px; }

.visitor-card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  overflow: hidden;
  transition: box-shadow 0.2s;
}
.visitor-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.1); }
.visitor-card.expanded { box-shadow: 0 4px 20px rgba(0,0,0,0.12); }

.visitor-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  cursor: pointer;
  transition: background 0.15s;
}
.visitor-header:hover { background: #fafafa; }

.visitor-info { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
.visitor-avatar {
  width: 42px; height: 42px; border-radius: 50%;
  background: linear-gradient(135deg, #e8f0eb, #d4e9d7);
  color: #5a8a6a; font-weight: 700; font-size: 18px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.visitor-basic { min-width: 0; }
.visitor-name { font-size: 15px; font-weight: 600; color: #2d3440; }
.visitor-meta { font-size: 12px; color: #8d95a3; margin-top: 2px; }

.visitor-tags-inline { display: flex; gap: 6px; flex-wrap: wrap; flex-shrink: 0; }

.expand-icon {
  color: #8d95a3; transition: transform 0.25s; flex-shrink: 0;
}
.expand-icon.rotated { transform: rotate(180deg); }

.visitor-detail {
  border-top: 1px solid #f0f0f0;
  padding: 20px;
  background: #fafaf8;
}

.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.detail-section { }
.section-title { font-size: 14px; font-weight: 600; color: #2d3440; margin: 0 0 12px; }

.chart-container { width: 100%; height: 220px; }

.tag-list { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.tag-row { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.tag-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.tag-name { color: #2d3440; flex: 1; }
.tag-score { color: #8d95a3; font-size: 12px; }
.no-data { color: #ccc; font-size: 13px; }

.sentiment-stats { display: flex; gap: 16px; margin-top: 12px; }
.stat-item { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #5a6577; }
.stat-dot { width: 8px; height: 8px; border-radius: 50%; }

.slide-enter-active { transition: all 0.25s ease; }
.slide-leave-active { transition: all 0.2s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; max-height: 0; padding-top: 0; padding-bottom: 0; }

@media (max-width: 800px) {
  .detail-grid { grid-template-columns: 1fr; }
  .visitor-tags-inline { display: none; }
}
</style>
