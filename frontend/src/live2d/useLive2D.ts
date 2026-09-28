/**
 * useLive2D — Vue 3 composable
 * 提供口型同步、表情切换、状态查询的响应式接口
 */
import { ref, onUnmounted } from 'vue'
import { LAppDelegate, ExpressionMap } from '@/live2d'
import { textToVisemeSequence } from '@/live2d/viseme-mapper'

export function useLive2D() {
  const isReady = ref(false)
  const currentExpression = ref('Normal')

  let _delegate: LAppDelegate | null = null
  let _lipSyncRAF = 0
  let _textVisemeRAF = 0

  function init(canvas: HTMLCanvasElement): boolean {
    _delegate = LAppDelegate.getInstance()
    const ok = _delegate.initialize(canvas)
    isReady.value = ok

    // 暴露全局测试函数（浏览器控制台调用）
    if (ok) {
      (window as any).__testMouth = (v: number) => {
        const model = _delegate?.getModel()
        console.log('[TEST] setLipSync', v, 'delegate:', !!_delegate, 'model:', !!model)
        setLipSync(v)
      }
    }

    return ok
  }

  function setLipSync(value: number) {
    _delegate?.getModel()?.setLipSyncValue(value)
  }

  function startLipSyncFromAnalyser(analyser: AnalyserNode) {
    stopLipSync()
    const dataArray = new Uint8Array(analyser.frequencyBinCount)
    const tick = () => {
      analyser.getByteTimeDomainData(dataArray)
      let sum = 0
      for (let i = 0; i < dataArray.length; i++) {
        const v = (dataArray[i] - 128) / 128
        sum += v * v
      }
      const rms = Math.sqrt(sum / dataArray.length)
      setLipSync(Math.min(1, rms * 3.5))
      _lipSyncRAF = requestAnimationFrame(tick)
    }
    _lipSyncRAF = requestAnimationFrame(tick)
  }

  function startTextLipSync(text: string, durationMs?: number) {
    stopTextLipSync()
    if (!text || !text.trim()) return
    const cleanText = text.replace(/\s+/g, '')
    const charCount = cleanText.length || 1
    const totalDuration = durationMs || Math.max(charCount * 180, 2000)
    const charDuration = totalDuration / charCount
    const sequence = textToVisemeSequence(text, charDuration)
    if (sequence.length === 0) return
    const startTime = performance.now()
    const totalSeqDuration = sequence[sequence.length - 1].time + charDuration
    const tick = () => {
      const elapsed = performance.now() - startTime
      if (elapsed / totalSeqDuration >= 1) {
        setLipSync(0)
        _delegate?.getModel()?.setMouthForm(0)
        return
      }
      let currentForm = 0
      let currentOpen = 0
      for (let i = sequence.length - 1; i >= 0; i--) {
        if (elapsed >= sequence[i].time) {
          currentForm = sequence[i].form
          currentOpen = sequence[i].open
          break
        }
      }
      setLipSync(currentOpen)
      _delegate?.getModel()?.setMouthForm(currentForm)
      _textVisemeRAF = requestAnimationFrame(tick)
    }
    _textVisemeRAF = requestAnimationFrame(tick)
  }

  function stopTextLipSync() {
    if (_textVisemeRAF) {
      cancelAnimationFrame(_textVisemeRAF)
      _textVisemeRAF = 0
    }
    setLipSync(0)
    _delegate?.getModel()?.setMouthForm(0)
  }

  function stopLipSync() {
    if (_lipSyncRAF) {
      cancelAnimationFrame(_lipSyncRAF)
      _lipSyncRAF = 0
    }
    stopTextLipSync()
    setLipSync(0)
  }

  function setExpression(emotion: string) {
    const mapped = ExpressionMap[emotion.toLowerCase()] || emotion
    currentExpression.value = mapped
    _delegate?.getModel()?.setExpressionByName(mapped)
  }

  function randomExpression() {
    _delegate?.getModel()?.setRandomExpression()
  }

  function onTouchBegan(e: any) {
    _delegate?.onTouchBegan(e.pageX ?? e.clientX, e.pageY ?? e.clientY)
  }

  function onTouchMoved(e: any) {
    _delegate?.onTouchMoved(e.pageX ?? e.clientX, e.pageY ?? e.clientY)
  }

  function onTouchEnded(e?: any) {
    if (e) {
      const px = e.pageX ?? e.clientX
      const py = e.pageY ?? e.clientY
      if (px !== undefined && py !== undefined) {
        _delegate?.onTouchEnded(px, py)
      }
    }
  }

  function updateLookSensitivity(eye: number, head: number, body: number) {
    _delegate?.getModel()?.updateLookSensitivity(eye, head, body)
  }
  function updateBreathIntensity(factor: number) {
    _delegate?.getModel()?.updateBreathIntensity(factor)
  }

  function resetLook() {
    _delegate?.getModel()?.setDragging(0, 0)
    const model: any = _delegate?.getModel()
    if (model?._dragManager) {
      const dm = model._dragManager
      dm.set(0, 0)
      dm._faceTargetX = 0
      dm._faceTargetY = 0
      dm._faceVX = 0
      dm._faceVY = 0
      dm._faceX = 0
      dm._faceY = 0
    }
  }

  function destroy() {
    stopLipSync()
    LAppDelegate.releaseInstance()
    _delegate = null
    isReady.value = false
  }

  onUnmounted(() => { destroy() })

  return {
    isReady,
    currentExpression,
    init,
    setLipSync,
    startLipSyncFromAnalyser,
    startTextLipSync,
    stopTextLipSync,
    stopLipSync,
    setExpression,
    randomExpression,
    onTouchBegan,
    onTouchMoved,
    onTouchEnded,
    updateLookSensitivity,
    updateBreathIntensity,
    resetLook,
    destroy,
  }
}