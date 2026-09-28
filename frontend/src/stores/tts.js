import { defineStore } from 'pinia'
import { ref } from 'vue'
import { synthesizeTTS } from '@/api/voice'
import { ElMessage } from 'element-plus'

/**
 * TTS 播放 Store（Pinia 全局单例）
 * 确保同一时间只有一条语音在播放
 */
export const useTtsStore = defineStore('tts', () => {
  let _audio = null
  let _revokeUrl = null
  const playingMessageKey = ref(null) // 当前正在播放的消息标识

  function stop() {
    if (_audio) {
      _audio.pause()
      _audio.onended = null
      _audio.onerror = null
      _audio = null
    }
    if (_revokeUrl) {
      _revokeUrl()
      _revokeUrl = null
    }
    playingMessageKey.value = null
  }

  /**
   * 播放指定消息的 TTS
   * @param {string} text 消息文本
   * @param {string} voice 音色 ID
   * @param {string} msgKey 消息唯一标识（用于判断是否同一条）
   */
  async function play(text, voice, msgKey) {
    const isSameMessage = playingMessageKey.value === msgKey

    // 停止当前播放（无论是同一条还是不同条）
    stop()

    // 如果是同一条消息正在播放 → 刚才已停止，直接 return（即"再点一次停止"）
    // 不对，用户需求是"再点一次从头重播"，所以这里不 return
    try {
      playingMessageKey.value = msgKey
      const blob = await synthesizeTTS(text, voice)
      const url = URL.createObjectURL(blob)
      _revokeUrl = () => URL.revokeObjectURL(url)

      _audio = new Audio(url)
      _audio.onended = () => {
        _audio = null
        _revokeUrl?.()
        _revokeUrl = null
        playingMessageKey.value = null
      }
      _audio.onerror = () => {
        _audio = null
        _revokeUrl?.()
        _revokeUrl = null
        playingMessageKey.value = null
      }
      await _audio.play()
    } catch {
      stop()
      ElMessage.warning('语音合成失败')
    }
  }

  /** 判断某条消息是否正在播放 */
  function isPlaying(msgKey) {
    return playingMessageKey.value === msgKey
  }

  return { playingMessageKey, play, stop, isPlaying }
})
