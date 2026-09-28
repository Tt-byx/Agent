<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getMarketingReport } from '@/api/analytics'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const reportData = ref(null)
const aiSuggestions = ref('')
const aiLoading = ref(false)

// Chart instances
let topSpendersChart = null
let freqVisitorsChart = null
let ageConsumptionChart = null
let genderConsumptionChart = null
let dwellChart = null
let seasonalChart = null

const palette = {
  sage: '#5a8a6a', sageLight: '#7ba88a', warm: '#c4956a',
  teal: '#6a858a', rose: '#c0705a', lavender: '#8b7bb5',
  gold: '#bfa76a', coral: '#d4827a', sky: '#6a9fb5',
}

async function fetchReport() {
  loading.value = true
  try {
    const data = await getMarketingReport()
    reportData.value = data
    await nextTick()
    renderCharts(data)
  } catch (e) {
    ElMessage.error('获取报告数据失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

function renderCharts(data) {
  if (!data) return
  // 1. 消费TOP10
  renderHorizontalBar('top-spenders-chart', data.topSpenders || [], 'nickname', 'total_spent', '总消费(¥)', palette.sage)
  // 2. 频次TOP10
  renderHorizontalBar('freq-visitors-chart', data.frequentVisitors || [], 'nickname', 'visit_count', '到访次数', palette.teal)
  // 3. 年龄消费偏好
  renderAgeConsumption(data.consumptionByAge || [])
  // 4. 性别消费偏好
  renderGenderConsumption(data.consumptionByGender || [])
  // 5. 逗留时长消费
  renderDwellChart(data.dwellConsumption || [])
  // 6. 淡旺季趋势
  renderSeasonalChart(data.seasonalTrend || [])
}

function renderHorizontalBar(id, data, nameKey, valueKey, seriesName, color) {
  const el = document.getElementById(id)
  if (!el || !data.length) return
  const chart = echarts.init(el)
  const sorted = [...data].reverse()
  chart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(45,52,64,0.92)', borderColor: 'transparent', textStyle: { color: '#fff', fontSize: 12 } },
    grid: { top: 8, right: 60, bottom: 12, left: 90 },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0efec', type: 'dashed' } }, axisLabel: { color: '#8d95a3', fontSize: 11 } },
    yAxis: {
      type: 'category',
      data: sorted.map(d => {
        const name = d[nameKey] || ''
        return name.length > 8 ? name.slice(0, 8) + '…' : name
      }),
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#5a6577', fontSize: 12 },
    },
    series: [{
      type: 'bar', barWidth: '55%', barMaxWidth: 20,
      data: sorted.map(d => ({
        value: Number(d[valueKey]) || 0,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: hexToRgba(color, 0.4) },
            { offset: 1, color: color },
          ]),
          borderRadius: [0, 4, 4, 0],
        },
      })),
      label: { show: true, position: 'right', fontSize: 11, color: '#8d95a3' },
    }],
  })
  if (id === 'top-spenders-chart') topSpendersChart = chart
  else freqVisitorsChart = chart
}

function renderAgeConsumption(data) {
  const el = document.getElementById('age-consumption-chart')
  if (!el || !data.length) return
  ageConsumptionChart = echarts.init(el)
  const categories = ['门票', '餐饮', '购物', '交通', '娱乐']
  const keys = ['avg_ticket', 'avg_food', 'avg_shopping', 'avg_transport', 'avg_entertainment']
  const colors = [palette.sage, palette.warm, palette.coral, palette.teal, palette.lavender]

  ageConsumptionChart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(45,52,64,0.92)', borderColor: 'transparent', textStyle: { color: '#fff', fontSize: 12 } },
    legend: { data: categories, bottom: 0, itemGap: 12, textStyle: { fontSize: 11, color: '#8d95a3' }, itemWidth: 10, itemHeight: 8 },
    grid: { top: 12, right: 16, bottom: 48, left: 56 },
    xAxis: { type: 'category', data: data.map(d => d.age_group), axisLine: { lineStyle: { color: '#eae8e4' } }, axisLabel: { color: '#8d95a3', fontSize: 11 }, axisTick: { show: false } },
    yAxis: { type: 'value', name: '元', splitLine: { lineStyle: { color: '#f0efec', type: 'dashed' } }, axisLabel: { color: '#8d95a3', fontSize: 11 } },
    series: categories.map((name, i) => ({
      name, type: 'bar',
      data: data.map(d => Number(d[keys[i]])?.toFixed(0) || 0),
      itemStyle: { color: colors[i], borderRadius: [3, 3, 0, 0] },
      barGap: '10%',
    })),
  })
}

function renderGenderConsumption(data) {
  const el = document.getElementById('gender-consumption-chart')
  if (!el || !data.length) return
  genderConsumptionChart = echarts.init(el)
  const categories = ['门票', '餐饮', '购物', '交通', '娱乐']
  const keys = ['avg_ticket', 'avg_food', 'avg_shopping', 'avg_transport', 'avg_entertainment']
  const colors = [palette.sage, palette.warm, palette.coral, palette.teal, palette.lavender]

  genderConsumptionChart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(45,52,64,0.92)', borderColor: 'transparent', textStyle: { color: '#fff' } },
    legend: { data: data.map(d => d.gender), bottom: 0, textStyle: { fontSize: 11, color: '#8d95a3' }, itemWidth: 10, itemHeight: 8 },
    grid: { top: 12, right: 16, bottom: 48, left: 56 },
    xAxis: { type: 'category', data: categories, axisLine: { lineStyle: { color: '#eae8e4' } }, axisLabel: { color: '#8d95a3', fontSize: 11 }, axisTick: { show: false } },
    yAxis: { type: 'value', name: '元', splitLine: { lineStyle: { color: '#f0efec', type: 'dashed' } }, axisLabel: { color: '#8d95a3', fontSize: 11 } },
    series: data.map((d, idx) => ({
      name: d.gender,
      type: 'bar',
      data: keys.map(k => Number(d[k])?.toFixed(0) || 0),
      itemStyle: { color: idx === 0 ? palette.teal : palette.rose, borderRadius: [3, 3, 0, 0] },
      barGap: '20%',
    })),
  })
}

function renderDwellChart(data) {
  const el = document.getElementById('dwell-chart')
  if (!el || !data.length) return
  dwellChart = echarts.init(el)

  dwellChart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(45,52,64,0.92)', borderColor: 'transparent', textStyle: { color: '#fff' } },
    grid: { top: 12, right: 16, bottom: 32, left: 56 },
    xAxis: { type: 'category', data: data.map(d => d.stay_group), axisLine: { lineStyle: { color: '#eae8e4' } }, axisLabel: { color: '#8d95a3', fontSize: 11 }, axisTick: { show: false } },
    yAxis: { type: 'value', name: '元', splitLine: { lineStyle: { color: '#f0efec', type: 'dashed' } }, axisLabel: { color: '#8d95a3', fontSize: 11 } },
    series: [{
      type: 'bar', barWidth: '50%',
      data: data.map((d, i) => ({
        value: Number(d.avg_cost)?.toFixed(0) || 0,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: palette.warm },
            { offset: 1, color: hexToRgba(palette.warm, 0.4) },
          ]),
          borderRadius: [4, 4, 0, 0],
        },
      })),
    }],
  })
}

function renderSeasonalChart(data) {
  const el = document.getElementById('seasonal-chart')
  if (!el || !data.length) return
  seasonalChart = echarts.init(el)

  seasonalChart.setOption({
    tooltip: {
      trigger: 'axis', backgroundColor: 'rgba(45,52,64,0.92)', borderColor: 'transparent', textStyle: { color: '#fff', fontSize: 12 },
      formatter: (params) => {
        const d = data[params[0].dataIndex]
        return `<div style="font-weight:600">${d.month}</div>
          <div>访客: ${d.visitor_count}</div>
          <div>收入: ¥${Number(d.total_revenue).toFixed(0)}</div>
          <div>满意度: ${Number(d.avg_satisfaction).toFixed(1)}</div>`
      },
    },
    legend: { data: ['访客量', '总收入'], bottom: 0, itemGap: 16, textStyle: { fontSize: 12, color: '#8d95a3' }, itemWidth: 12, itemHeight: 8 },
    grid: { top: 16, right: 60, bottom: 48, left: 56 },
    xAxis: { type: 'category', data: data.map(d => d.month), axisLine: { lineStyle: { color: '#eae8e4' } }, axisLabel: { color: '#8d95a3', fontSize: 11 }, axisTick: { show: false } },
    yAxis: [
      { type: 'value', name: '人数', splitLine: { lineStyle: { color: '#f0efec', type: 'dashed' } }, axisLabel: { color: '#8d95a3', fontSize: 11 } },
      { type: 'value', name: '收入(¥)', splitLine: { show: false }, axisLabel: { color: '#8d95a3', fontSize: 11 } },
    ],
    series: [
      {
        name: '访客量', type: 'bar', yAxisIndex: 0,
        data: data.map(d => d.visitor_count),
        itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: palette.sageLight }, { offset: 1, color: palette.sage }]), borderRadius: [4, 4, 0, 0] },
        barWidth: '35%',
      },
      {
        name: '总收入', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'circle', symbolSize: 6,
        data: data.map(d => Number(d.total_revenue)?.toFixed(0) || 0),
        itemStyle: { color: palette.warm }, lineStyle: { width: 2.5 },
      },
    ],
  })
}

async function generateAISuggestions() {
  if (!reportData.value) {
    ElMessage.warning('请先加载报告数据')
    return
  }
  aiLoading.value = true
  aiSuggestions.value = ''
  try {
    const summary = buildReportSummary()
    const res = await request.post('/api/analytics/marketing', { stats_summary: summary })
    aiSuggestions.value = res?.suggestions || '暂无建议'
  } catch (e) {
    aiSuggestions.value = '生成失败: ' + (e?.message || '未知错误')
  } finally {
    aiLoading.value = false
  }
}

function buildReportSummary() {
  const d = reportData.value
  if (!d) return ''
  let s = '景区营销分析报告数据摘要：'
  if (d.topSpenders?.length) s += `高消费游客TOP1为${d.topSpenders[0].nickname}(总消费¥${Number(d.topSpenders[0].total_spent).toFixed(0)})。`
  if (d.seasonalTrend?.length) {
    const peak = [...d.seasonalTrend].sort((a, b) => b.visitor_count - a.visitor_count)[0]
    s += `旺季月份${peak.month}(访客${peak.visitor_count}人)。`
  }
  if (d.consumptionByAge?.length) s += `包含${d.consumptionByAge.length}个年龄段的消费偏好数据。`
  if (d.dwellConsumption?.length) s += `逗留时长分${d.dwellConsumption.length}个区间与消费关联数据。`
  return s
}

function hexToRgba(hex, alpha) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

const allCharts = () => [topSpendersChart, freqVisitorsChart, ageConsumptionChart, genderConsumptionChart, dwellChart, seasonalChart]
function handleResize() { allCharts().forEach(c => c?.resize()) }

onMounted(() => {
  fetchReport()
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  allCharts().forEach(c => c?.dispose())
})
</script>

<template>
  <div class="report-page" v-loading="loading">
    <!-- Header -->
    <div class="report-header">
      <div>
        <h2 class="page-title">营销决策分析报告</h2>
        <p class="page-desc">基于游客行为数据的深度分析，识别高价值客群，制定精准营销策略</p>
      </div>
      <el-button type="primary" @click="fetchReport" class="refresh-btn" :loading="loading">
        <el-icon v-if="!loading"><Refresh /></el-icon> 刷新数据
      </el-button>
    </div>

    <template v-if="reportData">
      <!-- 1. 高价值画像识别 -->
      <div class="section-title">
        <el-icon><Trophy /></el-icon> 高价值画像识别
      </div>
      <div class="charts-row">
        <div class="panel">
          <div class="panel-head"><span class="panel-label">消费总额 TOP 10</span></div>
          <div id="top-spenders-chart" class="chart-area"></div>
        </div>
        <div class="panel">
          <div class="panel-head"><span class="panel-label">到访频次 TOP 10</span></div>
          <div id="freq-visitors-chart" class="chart-area"></div>
        </div>
      </div>

      <!-- 2. 消费偏好分析 -->
      <div class="section-title">
        <el-icon><ShoppingCart /></el-icon> 消费偏好分析
      </div>
      <div class="charts-row">
        <div class="panel">
          <div class="panel-head"><span class="panel-label">各年龄段消费偏好</span></div>
          <div id="age-consumption-chart" class="chart-area"></div>
        </div>
        <div class="panel">
          <div class="panel-head"><span class="panel-label">性别消费对比</span></div>
          <div id="gender-consumption-chart" class="chart-area"></div>
        </div>
      </div>

      <!-- 3. 逗留时长与消费 -->
      <div class="section-title">
        <el-icon><Timer /></el-icon> 逗留时长与消费关联
      </div>
      <div class="panel">
        <div class="panel-head"><span class="panel-label">不同逗留时长的平均消费</span></div>
        <div id="dwell-chart" class="chart-area"></div>
      </div>

      <!-- 4. 淡旺季趋势 -->
      <div class="section-title">
        <el-icon><Calendar /></el-icon> 淡旺季趋势
      </div>
      <div class="panel">
        <div class="panel-head"><span class="panel-label">月度访客量与收入趋势</span></div>
        <div id="seasonal-chart" class="chart-area chart-area--tall"></div>
      </div>

      <!-- 5. AI 营销建议 -->
      <div class="section-title">
        <el-icon><MagicStick /></el-icon> AI 营销建议
      </div>
      <div class="panel ai-panel">
        <div class="panel-head">
          <span class="panel-label">基于数据分析的营销策略推荐</span>
          <el-button size="small" :loading="aiLoading" @click="generateAISuggestions" class="ai-btn">
            <el-icon v-if="!aiLoading" :size="14"><MagicStick /></el-icon>
            {{ aiLoading ? '分析中...' : '生成营销建议' }}
          </el-button>
        </div>
        <div v-if="aiSuggestions" class="ai-content">{{ aiSuggestions }}</div>
        <div v-else class="ai-empty">点击"生成营销建议"，AI 将基于报告数据为您推荐精准营销策略</div>
      </div>
    </template>

    <div v-if="!loading && !reportData" class="empty-state">
      <p>暂无分析数据，请先在消费分析页面导入 xlsx 数据</p>
    </div>
  </div>
</template>

<style scoped>
.report-page {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.page-title {
  font-size: 20px; font-weight: 700; color: #2d3440; margin: 0;
}
.page-desc { font-size: 13px; color: #8d95a3; margin: 4px 0 0; }

.refresh-btn {
  background: #5a8a6a; border-color: #5a8a6a; border-radius: 8px; flex-shrink: 0;
}

.section-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 15px; font-weight: 600; color: #2d3440;
  margin-top: 8px; margin-bottom: 4px;
}

.panel {
  background: #fff; border-radius: 10px; border: 1px solid #eae8e4; padding: 20px;
  transition: box-shadow 0.2s;
}
.panel:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }

.panel-head {
  display: flex; align-items: baseline; gap: 10px;
  margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid #eae8e4;
}
.panel-label { font-size: 14px; font-weight: 600; color: #2d3440; }

.chart-area { width: 100%; height: 300px; }
.chart-area--tall { height: 360px; }

.charts-row {
  display: grid; grid-template-columns: 1fr 1fr; gap: 16px;
}

/* AI */
.ai-btn { border-color: #c4956a; color: #c4956a; }
.ai-content {
  font-size: 14px; line-height: 1.8; color: #2d3440; white-space: pre-wrap;
  padding: 12px 16px; background: #fdf8f0; border-radius: 8px; border-left: 3px solid #c4956a;
}
.ai-empty { font-size: 13px; color: #8d95a3; text-align: center; padding: 24px 0; }

.empty-state {
  text-align: center; padding: 60px 20px; color: #8d95a3; font-size: 14px;
  background: #fff; border-radius: 10px; border: 1px solid #eae8e4;
}

@media (max-width: 900px) {
  .charts-row { grid-template-columns: 1fr; }
  .report-header { flex-direction: column; align-items: flex-start; }
}
</style>
