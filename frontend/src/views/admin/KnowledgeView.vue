<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  uploadKnowledge,
  processKnowledge,
  getKnowledgeList,
  deleteKnowledge,
  reprocessKnowledge,
  updateKnowledge,
} from '@/api/knowledge'
import { ElMessage, ElMessageBox } from 'element-plus'

const documents = ref([])
const uploadTitle = ref('')
const uploadCategory = ref('未分类')
const uploadScenicArea = ref('')
const uploading = ref(false)
const uploadProgress = ref(0)
const selectedFile = ref(null)
let pollTimer = null

// 筛选
const filterCategory = ref('')
const filterScenicArea = ref('')

// 编辑弹窗
const editDialogVisible = ref(false)
const editForm = ref({ id: null, title: '', content: '', category: '', scenicArea: '' })
const editSaving = ref(false)

const categoryOptions = [
  '景区介绍', '票价政策', '交通指南', '游览路线',
  '美食推荐', '历史文化', '活动资讯', '未分类',
]

const stageLabels = {
  parsing: '解析文档',
  chunking: '切分文本',
  embedding: '向量化',
  storing: '存入数据库',
  completed: '处理完成',
  failed: '处理失败',
}

const fileTypeStyle = {
  docx: { color: '#5a8a6a', bg: '#e8f0eb' },
  xlsx: { color: '#c4956a', bg: '#f5ede4' },
  pdf:  { color: '#c0705a', bg: '#f5e8e4' },
}

const isProcessing = computed(() =>
  documents.value.some((d) => d.vectorStatus === 1)
)

function handleFileChange(file) {
  selectedFile.value = file.raw
  if (!uploadTitle.value) {
    uploadTitle.value = file.name.replace(/\.[^.]+$/, '')
  }
}

function handleFileRemove() {
  selectedFile.value = null
}

async function handleUpload() {
  if (!selectedFile.value) {
    ElMessage.warning('请选择文件')
    return
  }
  if (!uploadTitle.value.trim()) {
    ElMessage.warning('请输入文档标题')
    return
  }

  uploading.value = true
  uploadProgress.value = 0
  try {
    await uploadKnowledge(
      selectedFile.value,
      uploadTitle.value.trim(),
      uploadCategory.value,
      uploadScenicArea.value,
      (e) => {
        if (e.total) uploadProgress.value = Math.round((e.loaded / e.total) * 100)
      }
    )
    ElMessage.success('上传成功！')
    uploadTitle.value = ''
    uploadCategory.value = '未分类'
    uploadScenicArea.value = ''
    selectedFile.value = null
    uploadProgress.value = 0
    await fetchList()
  } catch (err) {
    ElMessage.error('上传失败: ' + (err.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

async function handleProcess(doc) {
  try {
    await processKnowledge(doc.id)
    ElMessage.info('开始处理文档...')
    await fetchList()
    startPolling()
  } catch (err) {
    ElMessage.error('启动处理失败: ' + (err.message || '未知错误'))
  }
}

async function fetchList() {
  try {
    const params = {}
    if (filterCategory.value) params.category = filterCategory.value
    if (filterScenicArea.value) params.scenicArea = filterScenicArea.value
    const data = await getKnowledgeList(params)
    documents.value = Array.isArray(data) ? data : []
  } catch (err) {
    console.error('获取文档列表失败:', err)
  }
}

function handleFilterChange() {
  fetchList()
}

function clearFilters() {
  filterCategory.value = ''
  filterScenicArea.value = ''
  fetchList()
}

async function handleDelete(doc) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档「${doc.title}」？`,
      '确认删除',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteKnowledge(doc.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (err) {
    if (err !== 'cancel') ElMessage.error('删除失败')
  }
}

async function handleReprocess(doc) {
  try {
    await reprocessKnowledge(doc.id)
    ElMessage.info('重新处理中...')
    await fetchList()
    startPolling()
  } catch (err) {
    ElMessage.error('重处理失败')
  }
}

function openEditDialog(doc) {
  editForm.value = {
    id: doc.id,
    title: doc.title || '',
    content: doc.content || '',
    category: doc.category || '未分类',
    scenicArea: doc.scenicArea || '',
  }
  editDialogVisible.value = true
}

async function handleEditSave() {
  if (!editForm.value.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  editSaving.value = true
  try {
    await updateKnowledge(editForm.value.id, {
      title: editForm.value.title,
      content: editForm.value.content,
      category: editForm.value.category,
      scenicArea: editForm.value.scenicArea,
    })
    ElMessage.success('保存成功，内容已更新时将自动重新向量化')
    editDialogVisible.value = false
    await fetchList()
  } catch (err) {
    ElMessage.error('保存失败: ' + (err.message || '未知错误'))
  } finally {
    editSaving.value = false
  }
}

function formatTime(time) {
  if (!time) return '—'
  const d = new Date(time)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    if (isProcessing.value) {
      fetchList()
    } else {
      stopPolling()
    }
  }, 1000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(() => {
  fetchList()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="page">
    <!-- 上传面板 -->
    <div class="panel">
      <div class="panel-head">
        <el-icon :size="16"><Upload /></el-icon>
        上传景区资料
      </div>
      <div class="upload-body">
        <el-input
          v-model="uploadTitle"
          placeholder="文档标题（如：灵山胜境游览指南）"
          class="title-input"
          clearable
        />
        <div class="upload-meta-row">
          <el-select v-model="uploadCategory" placeholder="选择分类" style="width: 200px">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
          <el-input
            v-model="uploadScenicArea"
            placeholder="关联景区（可选）"
            clearable
            style="flex: 1"
          />
        </div>
        <el-upload
          class="upload-area"
          drag
          :auto-upload="false"
          :limit="1"
          accept=".docx,.xlsx"
          :on-change="handleFileChange"
          :on-remove="handleFileRemove"
        >
          <div class="upload-inner">
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-text">拖拽文件到此处，或 <em>点击选择</em></div>
            <div class="upload-hint">支持 .docx、.xlsx 格式</div>
          </div>
        </el-upload>

        <div v-if="uploading" class="upload-progress">
          <el-progress
            :percentage="uploadProgress"
            :stroke-width="8"
            :format="(p) => p + '%'"
            color="#5a8a6a"
          />
          <span class="progress-hint">正在上传文件...</span>
        </div>

        <div class="upload-actions">
          <el-button
            type="primary"
            :loading="uploading"
            class="submit-btn"
            @click="handleUpload"
          >
            <el-icon v-if="!uploading"><Check /></el-icon>
            {{ uploading ? '上传中...' : '上传文件' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 文档列表 -->
    <div class="panel">
      <div class="panel-head">
        <el-icon :size="16"><FolderOpened /></el-icon>
        已上传文档
        <span class="count-badge" v-if="documents.length">{{ documents.length }}</span>
      </div>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-select
          v-model="filterCategory"
          placeholder="按分类筛选"
          clearable
          style="width: 180px"
          @change="handleFilterChange"
        >
          <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
        </el-select>
        <el-input
          v-model="filterScenicArea"
          placeholder="按景区筛选"
          clearable
          style="width: 200px"
          @input="handleFilterChange"
        />
        <el-button text @click="clearFilters">清除筛选</el-button>
      </div>

      <el-table
        v-if="documents.length"
        :data="documents"
        class="data-table"
        stripe
        size="default"
      >
        <el-table-column prop="title" label="文档标题" min-width="140">
          <template #default="{ row }">
            <div class="doc-name">
              <el-icon :size="14" :style="{ color: (fileTypeStyle[row.fileType] || fileTypeStyle.docx).color }">
                <Document />
              </el-icon>
              <span>{{ row.title }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="category" label="分类" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.category || '未分类' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="scenicArea" label="关联景区" width="120" align="center">
          <template #default="{ row }">
            <span class="scenic-text">{{ row.scenicArea || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="fileType" label="类型" width="70" align="center">
          <template #default="{ row }">
            <span
              class="type-chip"
              :style="{
                color: (fileTypeStyle[row.fileType] || fileTypeStyle.docx).color,
                background: (fileTypeStyle[row.fileType] || fileTypeStyle.docx).bg,
              }"
            >
              {{ (row.fileType || '').toUpperCase() }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="处理进度" min-width="200">
          <template #default="{ row }">
            <div v-if="row.vectorStatus === 1" class="progress-cell">
              <el-progress
                :percentage="row.processProgress || 0"
                :stroke-width="10"
                :format="() => ''"
                color="#5a8a6a"
                class="inline-progress"
              />
              <span class="progress-text">
                {{ stageLabels[row.processStage] || '处理中' }}
                {{ row.processProgress || 0 }}%
              </span>
            </div>
            <div v-else-if="row.vectorStatus === 2" class="status-done">
              <el-icon color="#5a8a6a"><CircleCheckFilled /></el-icon>
              <span>已完成 ({{ row.chunkCount || 0 }} 片段)</span>
            </div>
            <div v-else-if="row.vectorStatus === 3" class="status-fail">
              <el-icon color="#e05050"><CircleCloseFilled /></el-icon>
              <span>处理失败</span>
            </div>
            <div v-else class="status-pending">
              <el-icon color="#999"><Clock /></el-icon>
              <span>待处理</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="updatedAt" label="最后更新" width="140" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ formatTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              size="small"
              text
              @click="openEditDialog(row)"
            >
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button
              v-if="row.vectorStatus === 0"
              type="success"
              size="small"
              text
              @click="handleProcess(row)"
            >
              <el-icon><VideoPlay /></el-icon> 处理
            </el-button>
            <el-button
              v-if="row.vectorStatus === 3"
              type="warning"
              size="small"
              text
              @click="handleReprocess(row)"
            >
              <el-icon><RefreshRight /></el-icon> 重试
            </el-button>
            <el-button
              type="danger"
              size="small"
              text
              @click="handleDelete(row)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="empty">
        <el-empty description="暂无文档">
          <template #description>
            <span class="empty-desc">上传景区资料，让数字人拥有专业知识</span>
          </template>
        </el-empty>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <el-dialog
      v-model="editDialogVisible"
      title="编辑知识条目"
      width="680px"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px" label-position="left">
        <el-form-item label="标题">
          <el-input v-model="editForm.title" placeholder="文档标题" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="editForm.category" placeholder="选择分类" style="width: 100%">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联景区">
          <el-input v-model="editForm.scenicArea" placeholder="关联的景区名称" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="editForm.content"
            type="textarea"
            :rows="12"
            placeholder="知识条目内容"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="handleEditSave">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  max-width: 1060px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
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
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}

.count-badge {
  font-size: 11px;
  font-weight: 500;
  color: var(--text-tertiary);
  background: var(--bg-page);
  padding: 1px 8px;
  border-radius: 10px;
  margin-left: 4px;
}

/* ── Upload ── */
.upload-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.upload-meta-row {
  display: flex;
  gap: 12px;
}

.title-input :deep(.el-input__wrapper) {
  border-radius: 8px;
  box-shadow: 0 0 0 1px var(--border-medium) inset;
}

.upload-area :deep(.el-upload-dragger) {
  border: 2px dashed var(--border-medium);
  border-radius: 10px;
  background: var(--bg-page);
  padding: 28px 20px;
  transition: all 0.2s ease;
}

.upload-area :deep(.el-upload-dragger:hover) {
  border-color: var(--accent-sage);
  background: var(--accent-sage-light);
}

.upload-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.upload-icon {
  font-size: 36px;
  color: var(--text-tertiary);
}

.upload-text {
  font-size: 14px;
  color: var(--text-secondary);
}

.upload-text em {
  color: var(--accent-sage);
  font-style: normal;
  font-weight: 500;
}

.upload-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.upload-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.upload-progress :deep(.el-progress) {
  width: 100%;
}

.progress-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.upload-actions {
  display: flex;
  justify-content: flex-end;
}

.submit-btn {
  border-radius: 8px;
  background: var(--accent-sage);
  border-color: var(--accent-sage);
  padding: 10px 24px;
  font-weight: 500;
}

/* ── Filter bar ── */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

/* ── Table ── */
.data-table {
  --el-table-border-color: var(--border-light);
  --el-table-header-bg-color: var(--bg-page);
}

.doc-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
  color: var(--text-primary);
}

.type-chip {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.scenic-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.time-cell {
  font-size: 13px;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
}

/* ── Progress cell ── */
.progress-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.inline-progress {
  width: 100%;
}

.inline-progress :deep(.el-progress-bar__outer) {
  border-radius: 4px;
}

.inline-progress :deep(.el-progress-bar__inner) {
  border-radius: 4px;
}

.progress-text {
  font-size: 12px;
  color: var(--accent-sage);
  font-weight: 500;
}

/* ── Status cells ── */
.status-done,
.status-fail,
.status-pending {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.status-done span { color: #5a8a6a; }
.status-fail span { color: #e05050; }
.status-pending span { color: #999; }

/* ── Empty ── */
.empty {
  padding: 32px 0;
}

.empty-desc {
  color: var(--text-tertiary);
  font-size: 13px;
}
</style>
