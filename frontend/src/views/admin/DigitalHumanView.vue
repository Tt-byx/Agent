<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/api/request'

const config = ref({ voice: 'zh-CN-XiaoxiaoNeural', voice_speed: '1.0', model: 'aniya' })
const voices = ref({})
const loading = ref(false)
const previewing = ref(false)

// 可用模型列表
const models = [
  { value: 'aniya', label: 'Aniya（可爱少女）' },
]

async function fetchConfig() {
  loading.value = true
  try {
    const [cfgRes, voiceRes] = await Promise.all([
      request.get('/api/digital-human/config'),
      request.get('/api/digital-human/voices'),
    ])
    config.value = { ...config.value, ...cfgRes }
    voices.value = voiceRes || {}
  } catch (e) {
    console.error('获取配置失败:', e)
  } finally {
    loading.value = false
  }
}

async function saveConfig(key, value) {
  try {
    await request.put('/api/digital-human/config', { [key]: value })
    config.value[key] = value
    ElMessage.success('配置已保存')
  } catch {
    ElMessage.error('保存失败')
  }
}

async function previewVoice() {
  previewing.value = true
  try {
    const sampleText = '您好，欢迎来到景区，我是您的智能导游，很高兴为您服务！'
    const formData = new FormData()
    formData.append('text', sampleText)
    formData.append('voice', config.value.voice)
    const response = await fetch('/py-api/tts', { method: 'POST', body: formData })
    if (!response.ok) throw new Error('TTS 失败')
    const blob = await response.blob()
    const audio = new Audio(URL.createObjectURL(blob))
    audio.onended = () => { previewing.value = false }
    audio.onerror = () => { previewing.value = false }
    await audio.play()
  } catch (e) {
    ElMessage.error('试听失败: ' + (e.message || ''))
    previewing.value = false
  }
}

function handleVoiceChange(voiceId) {
  saveConfig('voice', voiceId)
}

function handleSpeedChange(val) {
  saveConfig('voice_speed', String(val))
}

onMounted(fetchConfig)
</script>

<template>
  <div class="dh-config" v-loading="loading">
    <div class="page-header">
      <h2 class="page-title">数字人配置</h2>
      <p class="page-desc">配置数字人的音色、语速等个性化参数</p>
    </div>

    <!-- 音色选择 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">语音音色</span>
        <el-button
          size="small"
          :loading="previewing"
          @click="previewVoice"
          class="preview-btn"
        >
          <el-icon v-if="!previewing" :size="14"><VideoPlay /></el-icon>
          {{ previewing ? '播放中...' : '试听当前音色' }}
        </el-button>
      </div>
      <div class="voice-grid">
        <div
          v-for="(info, id) in voices"
          :key="id"
          class="voice-card"
          :class="{ active: config.voice === id }"
          @click="handleVoiceChange(id)"
        >
          <div class="voice-avatar" :class="info.gender === '男' ? 'male' : 'female'">
            {{ info.name[0] }}
          </div>
          <div class="voice-info">
            <span class="voice-name">{{ info.name }}</span>
            <span class="voice-meta">{{ info.gender }} · {{ info.style }}</span>
          </div>
          <div v-if="config.voice === id" class="voice-check">✓</div>
        </div>
      </div>
    </div>

    <!-- 语速调节 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">语音语速</span>
        <span class="panel-value">{{ config.voice_speed }}x</span>
      </div>
      <div class="speed-control">
        <span class="speed-label">慢</span>
        <el-slider
          :model-value="parseFloat(config.voice_speed)"
          :min="0.5"
          :max="2.0"
          :step="0.1"
          :format-tooltip="v => v.toFixed(1) + 'x'"
          @change="handleSpeedChange"
          class="speed-slider"
        />
        <span class="speed-label">快</span>
      </div>
    </div>

    <!-- 数字人模型 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">数字人模型</span>
      </div>
      <el-select
        :model-value="config.model"
        @change="val => saveConfig('model', val)"
        style="width: 280px"
      >
        <el-option
          v-for="m in models"
          :key="m.value"
          :label="m.label"
          :value="m.value"
        />
      </el-select>
    </div>
  </div>
</template>

<style scoped>
.dh-config {
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header { margin-bottom: 4px; }
.page-title { font-size: 20px; font-weight: 700; color: #2d3440; margin: 0; }
.page-desc { font-size: 13px; color: #8d95a3; margin: 4px 0 0; }

.panel {
  background: #fff;
  border-radius: 10px;
  border: 1px solid #eae8e4;
  padding: 20px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eae8e4;
}

.panel-title { font-size: 14px; font-weight: 600; color: #2d3440; }
.panel-value { font-size: 14px; color: #5a8a6a; font-weight: 600; font-variant-numeric: tabular-nums; }

.preview-btn {
  border-color: #5a8a6a;
  color: #5a8a6a;
}

/* Voice grid */
.voice-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
}

.voice-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #eae8e4;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.voice-card:hover {
  border-color: #c0c4cc;
  background: #fafaf8;
}

.voice-card.active {
  border-color: #5a8a6a;
  background: #e8f0eb;
}

.voice-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.voice-avatar.female { background: linear-gradient(135deg, #e8a0b4, #c0708a); }
.voice-avatar.male { background: linear-gradient(135deg, #6a9fb5, #4a7f95); }

.voice-info { display: flex; flex-direction: column; min-width: 0; }
.voice-name { font-size: 14px; font-weight: 600; color: #2d3440; }
.voice-meta { font-size: 12px; color: #8d95a3; margin-top: 2px; }

.voice-check {
  margin-left: auto;
  color: #5a8a6a;
  font-weight: 700;
  font-size: 16px;
}

/* Speed slider */
.speed-control {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 8px;
}

.speed-label {
  font-size: 12px;
  color: #8d95a3;
  flex-shrink: 0;
}

.speed-slider {
  flex: 1;
  max-width: 400px;
}

:deep(.speed-slider .el-slider__runway) {
  background: #eae8e4;
}

:deep(.speed-slider .el-slider__bar) {
  background: #5a8a6a;
}

:deep(.speed-slider .el-slider__button) {
  border-color: #5a8a6a;
}
</style>
