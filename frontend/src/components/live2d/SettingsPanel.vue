<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  lookFactor: { type: Number, default: 8 },
  breathFactor: { type: Number, default: 1 },
  eyeFactor: { type: Number, default: 1.5 },
  bodyFactor: { type: Number, default: 3 },
})

const emit = defineEmits(['update:lookFactor', 'update:breathFactor', 'update:eyeFactor', 'update:bodyFactor', 'reset'])

const isOpen = ref(false)
const localLook = ref(props.lookFactor)
const localBreath = ref(props.breathFactor)
const localEye = ref(props.eyeFactor)
const localBody = ref(props.bodyFactor)

watch(localLook, v => emit('update:lookFactor', v))
watch(localBreath, v => emit('update:breathFactor', v))
watch(localEye, v => emit('update:eyeFactor', v))
watch(localBody, v => emit('update:bodyFactor', v))
</script>

<template>
  <div class="settings-wrapper">
    <button class="settings-toggle" @click="isOpen = !isOpen" :title="isOpen ? '关闭设置' : '打开设置'">
      {{ isOpen ? '✕' : '⚙' }}
    </button>

    <transition name="slide">
      <div v-if="isOpen" class="settings-panel">
        <h4>数字人参数</h4>

        <label>
          <span>👁 眼球灵敏度</span>
          <input type="range" min="0" max="5" step="0.5" v-model.number="localEye" />
          <span class="val">{{ localEye }}</span>
        </label>

        <label>
          <span>🗣 头部灵敏度</span>
          <input type="range" min="0" max="25" step="1" v-model.number="localLook" />
          <span class="val">{{ localLook }}</span>
        </label>

        <label>
          <span>🫁 身体灵敏度</span>
          <input type="range" min="0" max="10" step="0.5" v-model.number="localBody" />
          <span class="val">{{ localBody }}</span>
        </label>

        <label>
          <span>💨 呼吸幅度</span>
          <input type="range" min="0" max="5" step="0.5" v-model.number="localBreath" />
          <span class="val">{{ localBreath }}</span>
        </label>

        <button class="reset-btn" @click="$emit('reset')">恢复默认</button>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.settings-wrapper {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 20;
}

.settings-toggle {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: rgba(255,255,255,0.2);
  backdrop-filter: blur(8px);
  color: #fff;
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.settings-toggle:hover {
  background: rgba(255,255,255,0.35);
}

.settings-panel {
  position: absolute;
  top: 40px;
  right: 0;
  width: 220px;
  background: rgba(30, 30, 50, 0.92);
  backdrop-filter: blur(12px);
  border-radius: 10px;
  padding: 14px;
  color: #eee;
  font-size: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.3);
}

.settings-panel h4 {
  margin: 0 0 12px;
  font-size: 13px;
  text-align: center;
  color: #fff;
}

.settings-panel label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}
.settings-panel label span:first-child {
  flex: 0 0 80px;
  font-size: 11px;
}
.settings-panel input[type="range"] {
  flex: 1;
  accent-color: #667eea;
}
.val {
  flex: 0 0 24px;
  text-align: right;
  font-family: monospace;
  color: #aaa;
}

.reset-btn {
  width: 100%;
  margin-top: 6px;
  padding: 6px;
  border: 1px solid rgba(255,255,255,0.2);
  border-radius: 6px;
  background: transparent;
  color: #ccc;
  font-size: 11px;
  cursor: pointer;
}
.reset-btn:hover {
  background: rgba(255,255,255,0.1);
  color: #fff;
}

.slide-enter-active, .slide-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}
.slide-enter-from, .slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>