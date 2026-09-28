<script setup>
import { ref } from 'vue'

const emit = defineEmits(['sendText'])

const props = defineProps({
  loading: {
    type: Boolean,
    default: false,
  },
})

const inputText = ref('')

// --- 文字发送 ---
function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.loading) return
  emit('sendText', text)
  inputText.value = ''
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="chat-input">
    <el-input
      v-model="inputText"
      type="textarea"
      :rows="2"
      placeholder="输入你的问题，Enter 发送，Shift + Enter 换行"
      resize="none"
      :disabled="loading"
      @keydown="handleKeydown"
    />

    <div class="button-group">
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!inputText.trim()"
        @click="handleSend"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  padding: 16px 20px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.button-group {
  display: flex;
  gap: 10px;
  margin-top: 10px;
  align-items: center;
  justify-content: flex-end;
}

.button-group .el-button {
  height: 40px;
  min-width: 96px;
}
</style>
