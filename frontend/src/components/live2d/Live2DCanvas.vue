<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useLive2D } from '@/live2d/useLive2D'

const emit = defineEmits(['ready', 'error'])
const canvasRef = ref(null)
const live2d = useLive2D()

onMounted(() => {
  if (!canvasRef.value) return
  if (typeof Live2DCubismCore === 'undefined') {
    emit('error', 'Cubism Core 未加载')
    return
  }

  try {
    const ok = live2d.init(canvasRef.value)
    if (ok) {
      emit('ready')
    } else {
      emit('error', 'WebGL 初始化失败')
    }
  } catch (e) {
    emit('error', e.message)
  }
})

defineExpose({
  setLipSync: live2d.setLipSync,
  startLipSyncFromAnalyser: live2d.startLipSyncFromAnalyser,
  startTextLipSync: live2d.startTextLipSync,
  stopTextLipSync: live2d.stopTextLipSync,
  stopLipSync: live2d.stopLipSync,
  setExpression: live2d.setExpression,
  isReady: live2d.isReady,
})
</script>

<template>
  <div class="live2d-container">
    <canvas ref="canvasRef" class="live2d-canvas" />
  </div>
</template>

<style scoped>
.live2d-container {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.live2d-canvas {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
