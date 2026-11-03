<script setup>
/**
 * 输入区 —— 对照 chat.deepseek.com 的双层结构重做。
 *
 * 改版前：一个 el-input textarea + 下方两个 el-button（"停止生成" / "发送"），
 * 视觉上是「表单 + 按钮」，和对话本身没有关系。
 *
 * 改版后：整块是一个大圆角容器，内部两层
 *   上层  纯文字输入（无边框、随内容自动增高，最高 200px）
 *   下层  左侧操作提示 + 右侧圆形发送按钮（流式时变成「停止」）
 * 这样输入框看起来像「对话的一部分」，而不是页面底部的一个表单控件。
 */
import { ref, nextTick, watch } from 'vue'

const emit = defineEmits(['sendText', 'stop'])

const props = defineProps({
  loading: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
})

const inputText = ref('')
const ta = ref(null)

/** 随内容自动增高：先归零再按 scrollHeight 撑开，避免删除文字后高度不回落 */
async function autoGrow() {
  await nextTick()
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

watch(inputText, autoGrow)

function handleSend() {
  const text = inputText.value.trim()
  if (!text || props.loading) return
  emit('sendText', text)
  inputText.value = ''
  nextTick(autoGrow)
}

function handleStop() {
  emit('stop')
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="composer" :class="{ 'is-busy': loading }">
    <textarea
      ref="ta"
      v-model="inputText"
      class="composer-input"
      rows="1"
      placeholder="给 AI 导游发送消息"
      :disabled="loading"
      @keydown="handleKeydown"
    ></textarea>

    <div class="composer-bar">
      <span class="composer-hint">Enter 发送 · Shift+Enter 换行</span>

      <div class="composer-actions">
        <!-- 流式输出中：给一个明确的「停下」入口，而不是让用户干等 -->
        <button
          v-if="streaming"
          class="btn-stop"
          type="button"
          @click="handleStop"
        >
          <span class="stop-square" aria-hidden="true"></span>
          停止
        </button>

        <button
          class="btn-send"
          type="button"
          :disabled="!inputText.trim() || loading"
          aria-label="发送"
          @click="handleSend"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
            <path d="M12 19V5M12 5l-6 6M12 5l6 6" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  display: flex;
  flex-direction: column;
  padding: 12px 14px 10px;
  background: var(--color-surface);
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-1);
  transition: border-color var(--dur-fast) ease-out,
              box-shadow var(--dur-fast) ease-out;
}

.composer:focus-within {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px var(--color-brand-glow), var(--shadow-1);
}

.composer.is-busy {
  background: var(--color-bg-tint);
}

.composer-input {
  width: 100%;
  max-height: 200px;
  padding: 2px 2px 6px;
  border: none;
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-base);
  line-height: 1.7;
  color: var(--color-ink);
  resize: none;
  outline: none;
  overflow-y: auto;
}

.composer-input::placeholder {
  color: var(--color-muted);
}

.composer-input:disabled {
  cursor: not-allowed;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding-top: 2px;
}

.composer-hint {
  font-size: var(--text-xs);
  color: var(--color-muted);
  letter-spacing: 0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.composer-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

/* 发送：圆形实心，只在有内容时可用 —— 空心/灰色表示「还不能发」 */
.btn-send {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: var(--radius-full);
  background: var(--color-brand);
  color: #fff;
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              transform var(--dur-fast) ease-out;
}

.btn-send:hover:not(:disabled) {
  background: var(--color-brand-hover);
  transform: translateY(-1px);
}

.btn-send:disabled {
  background: var(--color-line-strong);
  color: var(--color-muted);
  cursor: not-allowed;
}

.btn-send:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

/* 停止：文字 + 一个小方块，比纯图标更容易理解 */
.btn-stop {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-full);
  background: var(--color-surface);
  font-family: var(--font-body);
  font-size: var(--text-xs);
  color: var(--color-ink-soft);
  cursor: pointer;
  transition: border-color var(--dur-fast) ease-out,
              color var(--dur-fast) ease-out;
}

.btn-stop:hover {
  border-color: var(--color-ink-soft);
  color: var(--color-ink);
}

.stop-square {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  background: currentColor;
}

@media (max-width: 640px) {
  .composer-hint { display: none; }
}
</style>
