<script setup>
import { ref, onMounted, computed } from 'vue'
import { getOpsLedger } from '@/api/ops'
import { ElMessage } from 'element-plus'

/**
 * Wave 4 · 成本 / 延迟 / 可靠性台账。
 *
 * 这是把后端"经营数据"可视化的页面：模型调用花了多少钱、语义缓存省了多少、
 * 各路由的 token 消耗、熔断器状态。
 *
 * 重要提醒（页面顶部也写了）：数据源是进程内 Micrometer 计数，**服务重启即归零**，
 * 只反映"本次启动以来"的情况。要看长期趋势应接 Prometheus + Grafana。
 */
const loading = ref(false)
const ledger = ref(null)

async function load() {
  loading.value = true
  try {
    ledger.value = await getOpsLedger()
  } catch (e) {
    ElMessage.error('加载台账失败：' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

const cost = computed(() => ledger.value?.cost || {})
const latency = computed(() => ledger.value?.latency || {})
const reliability = computed(() => ledger.value?.reliability || {})

/** latency 是「按接口的耗时数组」（每个元素含 method/uri/status/count/avgMs/p50/p95/p99/max） */
const latencyRows = computed(() => (Array.isArray(latency.value) ? latency.value : []))

/** reliability 里除对象外的标量字段（agentRuns 等）用描述列表展示 */
const reliabilityScalars = computed(() => {
  const out = {}
  for (const [k, v] of Object.entries(reliability.value)) {
    if (v === null || typeof v !== 'object') out[k] = v
  }
  return out
})

/** reliability 里的 Map-like 字段（routeCalls / degradeByType / rateLimitRejected） */
const reliabilityGroups = computed(() => {
  const out = []
  for (const [k, v] of Object.entries(reliability.value)) {
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      out.push({
        key: k,
        rows: Object.entries(v).map(([name, count]) => ({ name, count })),
      })
    }
  }
  return out
})

function fmtCost(v) {
  if (v === undefined || v === null) return '—'
  return `¥ ${Number(v).toFixed(4)}`
}
function fmtPct(v) {
  if (v === undefined || v === null) return '—'
  return `${(Number(v) * 100).toFixed(1)}%`
}

onMounted(load)
</script>

<template>
  <div class="ops-view" v-loading="loading">
    <div class="page-header">
      <h2>成本与性能台账</h2>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="数据源说明"
      description="以下指标来自服务端进程内计数，服务重启后归零，仅反映本次启动以来的用量。长期趋势请以 Prometheus 为准。"
      style="margin-bottom: 16px;"
    />

    <!-- 成本卡片 -->
    <el-row :gutter="16">
      <el-col :span="6">
        <div class="stat-card primary">
          <div class="stat-label">累计成本</div>
          <div class="stat-value">{{ fmtCost(cost.totalCost) }}</div>
          <div class="stat-sub">{{ cost.currency || 'CNY' }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">输入 Token</div>
          <div class="stat-value">{{ cost.totalInputTokens ?? '—' }}</div>
          <div class="stat-sub">tokens</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">输出 Token</div>
          <div class="stat-value">{{ cost.totalOutputTokens ?? '—' }}</div>
          <div class="stat-sub">tokens</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card success">
          <div class="stat-label">语义缓存命中率</div>
          <div class="stat-value">{{ fmtPct(cost.semanticCache?.hitRate) }}</div>
          <div class="stat-sub">
            命中 {{ cost.semanticCache?.hits ?? 0 }} / 未中 {{ cost.semanticCache?.misses ?? 0 }}
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 缓存收益 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header><span class="card-title">语义缓存收益</span></template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="估算节省">
          {{ fmtCost(cost.semanticCache?.estimatedSavingCNY) }}
        </el-descriptions-item>
        <el-descriptions-item label="单次模型调用均成本">
          ¥ {{ cost.semanticCache?.avgCostPerModelCallCNY ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="命中率">
          {{ fmtPct(cost.semanticCache?.hitRate) }}
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="cost.semanticCache?.explain" class="explain">
        {{ cost.semanticCache.explain }}
      </div>
    </el-card>

    <!-- 按路由明细 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header><span class="card-title">按模型路由明细</span></template>
      <el-table :data="cost.byRoute || []" stripe size="small">
        <el-table-column prop="route" label="路由" min-width="200" />
        <el-table-column prop="inputTokens" label="输入 Token" width="120" align="right" />
        <el-table-column prop="outputTokens" label="输出 Token" width="120" align="right" />
        <el-table-column label="单价(输入/1k)" width="130" align="right">
          <template #default="{ row }">¥ {{ row.pricePer1kInput }}</template>
        </el-table-column>
        <el-table-column label="单价(输出/1k)" width="130" align="right">
          <template #default="{ row }">¥ {{ row.pricePer1kOutput }}</template>
        </el-table-column>
        <el-table-column label="成本" width="110" align="right">
          <template #default="{ row }">
            <strong>{{ fmtCost(row.costCNY) }}</strong>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!(cost.byRoute || []).length" description="暂无模型调用记录" />
    </el-card>

    <!-- 延迟 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header><span class="card-title">接口延迟（按接口聚合）</span></template>
      <el-table :data="latencyRows" stripe size="small" max-height="420">
        <el-table-column label="方法" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.method === 'GET' ? 'success' : 'primary'">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="uri" label="接口" min-width="240" />
        <el-table-column prop="status" label="状态" width="70" align="center" />
        <el-table-column prop="count" label="次数" width="80" align="right" />
        <el-table-column label="平均" width="90" align="right">
          <template #default="{ row }">{{ row.avgMs }} ms</template>
        </el-table-column>
        <el-table-column label="P50" width="90" align="right">
          <template #default="{ row }">{{ row.p50Ms }} ms</template>
        </el-table-column>
        <el-table-column label="P95" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ slow: row.p95Ms > 1000 }">{{ row.p95Ms }} ms</span>
          </template>
        </el-table-column>
        <el-table-column label="P99" width="90" align="right">
          <template #default="{ row }">{{ row.p99Ms }} ms</template>
        </el-table-column>
        <el-table-column label="最大" width="90" align="right">
          <template #default="{ row }">{{ row.maxMs }} ms</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!latencyRows.length" description="暂无接口耗时数据" />
    </el-card>

    <!-- 可靠性 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header><span class="card-title">可靠性</span></template>

      <el-descriptions v-if="Object.keys(reliabilityScalars).length" :column="3" border>
        <el-descriptions-item
          v-for="(v, k) in reliabilityScalars"
          :key="k"
          :label="String(k)"
        >
          {{ v }}
        </el-descriptions-item>
      </el-descriptions>

      <div
        v-for="g in reliabilityGroups"
        :key="g.key"
        class="group-block"
      >
        <div class="group-title">{{ g.key }}</div>
        <el-table :data="g.rows" size="small" stripe>
          <el-table-column prop="name" label="项" min-width="220" />
          <el-table-column prop="count" label="次数" width="120" align="right" />
        </el-table>
      </div>

      <el-empty
        v-if="!Object.keys(reliabilityScalars).length && !reliabilityGroups.length"
        description="暂无可靠性数据"
      />
    </el-card>
  </div>
</template>

<style scoped>
.ops-view { padding: 20px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; color: #303133; }
.stat-card { background: #fff; border-radius: 10px; padding: 18px 20px; box-shadow: 0 1px 6px rgba(0,0,0,0.06); border-left: 4px solid #dcdfe6; }
.stat-card.primary { border-left-color: #409eff; }
.stat-card.success { border-left-color: #67c23a; }
.stat-label { font-size: 13px; color: #909399; margin-bottom: 8px; }
.stat-value { font-size: 24px; font-weight: 600; color: #303133; line-height: 1.2; }
.stat-sub { font-size: 12px; color: #c0c4cc; margin-top: 6px; }
.card-title { font-weight: 600; color: #303133; }
.explain { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.6; }
.slow { color: #e6a23c; font-weight: 600; }
.group-block { margin-top: 14px; }
.group-title { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 8px; }
</style>
