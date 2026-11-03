<script setup>
import { ref, onMounted } from 'vue'
import { reconcile, probe, runEval, adversarial, reloadIndex } from '@/api/rag'
import { ElMessage } from 'element-plus'

/**
 * Wave 6 · RAG 运维台。
 *
 * 把后端分散的运维接口集中到一个页面，方便评测与排障：
 *   · 向量对账   —— 发现 MySQL 记录条数与 pgvector 实际条数的不一致
 *   · 检索探针   —— 看某问题召回了哪些切片、得分多少
 *   · 离线评测   —— Hit@1 / Recall@K / MRR
 *   · 对抗样本   —— 验证语义缓存阈值是否会把 A 的答案给 B
 *   · 重建索引   —— 文档增删后刷新 BM25
 */

// ── 对账 ──
const reconcileLoading = ref(false)
const reconcileData = ref(null)
async function doReconcile() {
  reconcileLoading.value = true
  try {
    reconcileData.value = await reconcile()
    const d = reconcileData.value
    if (d.vectorStoreReachable && d.documentsMismatched === 0) {
      ElMessage.success(`对账通过：${d.documentsConsistent} 篇一致`)
    } else if (!d.vectorStoreReachable) {
      ElMessage.warning(d.message || '向量库不可达')
    } else {
      ElMessage.warning(`发现 ${d.documentsMismatched} 篇不一致`)
    }
  } catch (e) {
    ElMessage.error('对账失败：' + (e.message || ''))
  } finally {
    reconcileLoading.value = false
  }
}

// ── 检索探针 ──
const probeQuery = ref('')
const probeLoading = ref(false)
const probeData = ref(null)
async function doProbe() {
  if (!probeQuery.value.trim()) return
  probeLoading.value = true
  try {
    probeData.value = await probe(probeQuery.value.trim())
  } catch (e) {
    ElMessage.error('探针失败：' + (e.message || ''))
  } finally {
    probeLoading.value = false
  }
}

// 探针返回可能是数组，也可能是含 chunks 的对象，统一成列表
const probeList = ref([])
function normalizeProbe(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.chunks)) return data.chunks
  if (data && Array.isArray(data.results)) return data.results
  return []
}

// ── 评测 ──
const evalLoading = ref(false)
const evalData = ref(null)
async function doEval() {
  evalLoading.value = true
  try {
    evalData.value = await runEval()
    ElMessage.success('评测完成')
  } catch (e) {
    ElMessage.error('评测失败：' + (e.message || ''))
  } finally {
    evalLoading.value = false
  }
}

// ── 对抗样本 ──
const advLoading = ref(false)
const advData = ref(null)
const advThreshold = ref(0.92)
async function doAdversarial() {
  advLoading.value = true
  try {
    advData.value = await adversarial(advThreshold.value)
  } catch (e) {
    ElMessage.error('对抗检验失败：' + (e.message || ''))
  } finally {
    advLoading.value = false
  }
}

// ── 重建索引 ──
const reloadLoading = ref(false)
async function doReload() {
  reloadLoading.value = true
  try {
    const r = await reloadIndex()
    ElMessage.success(`BM25 索引已重建：${r.chunks ?? '?'} 条切片`)
  } catch (e) {
    ElMessage.error('重建失败：' + (e.message || ''))
  } finally {
    reloadLoading.value = false
  }
}

function pct(v) {
  if (v === undefined || v === null) return '—'
  return `${(Number(v) * 100).toFixed(1)}%`
}
function num(v, digits = 4) {
  if (v === undefined || v === null) return '—'
  return Number(v).toFixed(digits)
}

onMounted(() => {
  doReconcile()
})
</script>

<template>
  <div class="rag-view">
    <div class="page-header">
      <h2>RAG 运维台</h2>
      <el-button :loading="reloadLoading" @click="doReload">
        <el-icon><RefreshRight /></el-icon> 重建 BM25 索引
      </el-button>
    </div>

    <!-- 对账 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">向量库对账</span>
          <el-button size="small" :loading="reconcileLoading" @click="doReconcile">重新对账</el-button>
        </div>
      </template>

      <div v-if="reconcileData" v-loading="reconcileLoading">
        <el-alert
          v-if="!reconcileData.vectorStoreReachable"
          type="error"
          :closable="false"
          show-icon
          :title="reconcileData.message || '向量库不可达'"
        />
        <template v-else>
          <el-row :gutter="16">
            <el-col :span="6">
              <div class="mini-stat">
                <div class="mini-label">向量总数</div>
                <div class="mini-value">{{ reconcileData.totalVectorsInStore }}</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-stat">
                <div class="mini-label">检查文档</div>
                <div class="mini-value">{{ reconcileData.documentsChecked }}</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-stat ok">
                <div class="mini-label">一致</div>
                <div class="mini-value">{{ reconcileData.documentsConsistent }}</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-stat" :class="{ bad: reconcileData.documentsMismatched > 0 }">
                <div class="mini-label">不一致</div>
                <div class="mini-value">{{ reconcileData.documentsMismatched }}</div>
              </div>
            </el-col>
          </el-row>

          <el-table
            v-if="reconcileData.mismatches?.length"
            :data="reconcileData.mismatches"
            stripe
            size="small"
            style="margin-top: 14px;"
          >
            <el-table-column prop="docId" label="文档ID" width="90" />
            <el-table-column prop="title" label="标题" min-width="160" />
            <el-table-column prop="expectedChunks" label="应有切片" width="100" align="right" />
            <el-table-column prop="actualVectors" label="实际向量" width="100" align="right" />
            <el-table-column prop="action" label="建议操作" min-width="220" />
          </el-table>
        </template>
      </div>
      <el-empty v-else description="尚未对账" />
    </el-card>

    <!-- 检索探针 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header><span class="card-title">检索探针</span></template>
      <div class="probe-bar">
        <el-input
          v-model="probeQuery"
          placeholder="输入一个问题，看它召回了哪些知识切片"
          @keyup.enter="doProbe"
          clearable
        />
        <el-button type="primary" :loading="probeLoading" @click="doProbe">检索</el-button>
      </div>
      <div v-if="probeData" style="margin-top: 12px;">
        <el-table :data="normalizeProbe(probeData)" stripe size="small" max-height="360">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column label="内容" min-width="360">
            <template #default="{ row }">
              {{ row.text || row.content || row.chunk || JSON.stringify(row) }}
            </template>
          </el-table-column>
          <el-table-column label="得分" width="110" align="right">
            <template #default="{ row }">
              {{ row.score !== undefined ? num(row.score, 4) : (row.rerankScore !== undefined ? num(row.rerankScore, 4) : '—') }}
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="normalizeProbe(probeData).length === 0" description="没有召回结果" />
      </div>
    </el-card>

    <!-- 离线评测 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header>
        <div class="card-head">
          <span class="card-title">离线评测（Hit@1 / Recall@K / MRR）</span>
          <el-button size="small" type="primary" :loading="evalLoading" @click="doEval">运行评测</el-button>
        </div>
      </template>
      <div v-if="evalData">
        <el-row :gutter="16">
          <el-col :span="8">
            <div class="mini-stat ok">
              <div class="mini-label">Hit@1</div>
              <div class="mini-value">{{ pct(evalData.hitAt1 ?? evalData.hit1) }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="mini-stat">
              <div class="mini-label">Recall@K</div>
              <div class="mini-value">{{ pct(evalData.recallAtK ?? evalData.recall) }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="mini-stat">
              <div class="mini-label">MRR</div>
              <div class="mini-value">{{ num(evalData.mrr, 3) }}</div>
            </div>
          </el-col>
        </el-row>
        <pre class="raw-json">{{ JSON.stringify(evalData, null, 2) }}</pre>
      </div>
      <el-empty v-else description="尚未运行评测" />
    </el-card>

    <!-- 对抗样本 -->
    <el-card shadow="never" style="margin-top: 16px;">
      <template #header>
        <div class="card-head">
          <span class="card-title">对抗样本检验（语义缓存阈值安全性）</span>
          <div class="adv-controls">
            <span class="adv-label">相似度阈值</span>
            <el-input-number v-model="advThreshold" :min="0.5" :max="1" :step="0.01" size="small" controls-position="right" style="width: 120px;" />
            <el-button size="small" type="primary" :loading="advLoading" @click="doAdversarial">检验</el-button>
          </div>
        </div>
      </template>
      <div v-if="advData">
        <pre class="raw-json">{{ JSON.stringify(advData, null, 2) }}</pre>
      </div>
      <el-empty v-else description="尚未检验" />
    </el-card>
  </div>
</template>

<style scoped>
.rag-view { padding: 20px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; color: var(--color-ink); }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-weight: 600; color: var(--color-ink); }
.mini-stat { background: var(--color-bg-tint); border-radius: 8px; padding: 14px 16px; text-align: center; }
.mini-stat.ok { background: #f0f9eb; }
.mini-stat.bad { background: #fef0f0; }
.mini-label { font-size: 12px; color: var(--color-muted); margin-bottom: 6px; }
.mini-value { font-size: 22px; font-weight: 600; color: var(--color-ink); }
.probe-bar { display: flex; gap: 10px; }
.adv-controls { display: flex; align-items: center; gap: 8px; }
.adv-label { font-size: 12px; color: var(--color-muted); }
.raw-json { background: var(--color-bg-tint); border-radius: 8px; padding: 12px; font-size: 12px; line-height: 1.6; max-height: 300px; overflow: auto; margin: 12px 0 0; }
</style>
