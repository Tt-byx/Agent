/**
 * 音频播放工具类
 * 使用 Web Audio API，支持 AnalyserNode 实时音频分析（用于口型同步）
 *
 * 队列模式：所有音频 chunk 排队播放，同一时间只有一段音频在播放。
 * 前一段播完后自动播放下一段，避免多段语音重叠。
 */
export class AudioPlayer {
  constructor() {
    this.audioContext = null
    this.analyser = null

    // 收集窗口：同一批到达的 chunk 会拼接为一段连续音频
    this._pendingChunks = []
    this._collectTimer = null
    this._collectDelay = 200

    // 播放队列：已解码的 AudioBuffer 列表，逐个播放
    this._playQueue = []
    this._isPlaying = false
    this._currentSource = null

    // 回调钩子
    this.onPlayStart = null  // callback(durationSeconds)
    this.onPlayEnd = null
  }

  init() {
    if (this.audioContext) return
    this.audioContext = new (window.AudioContext || window.webkitAudioContext)()
    this.analyser = this.audioContext.createAnalyser()
    this.analyser.fftSize = 256
    this.analyser.smoothingTimeConstant = 0.5
    this.analyser.connect(this.audioContext.destination)
  }

  /**
   * 将 Base64 音频数据加入收集窗口
   * 窗口到期后拼接为一段音频，加入播放队列
   */
  enqueue(base64Data, format = 'wav') {
    this._pendingChunks.push(base64Data)

    // 重置收集定时器
    if (this._collectTimer) clearTimeout(this._collectTimer)
    this._collectTimer = setTimeout(() => this._flushAndEnqueue(), this._collectDelay)
  }

  /**
   * 将收集窗口中的 chunk 解码拼接后加入播放队列，然后触发播放
   */
  async _flushAndEnqueue() {
    this._collectTimer = null
    if (this._pendingChunks.length === 0) return

    const chunks = this._pendingChunks.splice(0)

    try {
      if (!this.audioContext) this.init()
      if (this.audioContext.state === 'suspended') {
        await this.audioContext.resume()
      }

      // 解码所有 chunk
      const decodedBuffers = []
      for (const base64 of chunks) {
        try {
          const buf = this._base64ToArrayBuffer(base64)
          console.log('[AudioPlayer] decoding chunk, bytes:', buf.byteLength)
          const decoded = await this.audioContext.decodeAudioData(buf)
          console.log('[AudioPlayer] decoded OK, duration:', decoded.duration.toFixed(2), 's')
          decodedBuffers.push(decoded)
        } catch (e) {
          console.warn('[AudioPlayer] 解码失败，跳过:', e)
        }
      }

      if (decodedBuffers.length === 0) {
        // 所有 chunk 解码失败，如果队列也空了则触发 onPlayEnd
        if (this._playQueue.length === 0 && !this._isPlaying) {
          this.onPlayEnd?.()
        }
        return
      }

      // 拼接为一段连续音频，加入播放队列
      const merged = this._mergeAudioBuffers(decodedBuffers)
      this._playQueue.push(merged)

      // 触发播放循环
      this._drainQueue()

    } catch (e) {
      console.warn('音频处理异常:', e)
    }
  }

  /**
   * 播放循环：从队列中逐个取出 AudioBuffer 播放
   * 同一时间只有一个播放在进行
   */
  async _drainQueue() {
    if (this._isPlaying) return  // 正在播放，等当前播完后会自动继续
    if (this._playQueue.length === 0) {
      // 队列空了，检查是否还有收集中的 chunk
      if (this._pendingChunks.length > 0) {
        this._flushAndEnqueue()
        return
      }
      // 全部播完
      this.onPlayEnd?.()
      return
    }

    this._isPlaying = true
    const buffer = this._playQueue.shift()

    // 通知开始播放
    const duration = buffer.duration
      this.onPlayStart?.(duration)

    await this._playBuffer(buffer)

    this._isPlaying = false

    // 继续播放队列中的下一段
    this._drainQueue()
  }

  /**
   * 播放单个 AudioBuffer
   */
  _playBuffer(audioBuffer) {
    return new Promise((resolve) => {
      const source = this.audioContext.createBufferSource()
      source.buffer = audioBuffer
      source.connect(this.analyser)
      // analyser 已经在 init() 中连接到 destination，音频自动输出

      this._currentSource = source

      source.onended = () => {
        this._currentSource = null
        resolve()
      }

      source.start(0)
    })
  }

  _mergeAudioBuffers(buffers) {
    if (buffers.length === 1) return buffers[0]

    const sampleRate = buffers[0].sampleRate
    const channels = buffers[0].numberOfChannels
    let totalLength = 0
    for (const buf of buffers) totalLength += buf.length

    const merged = this.audioContext.createBuffer(channels, totalLength, sampleRate)
    for (let ch = 0; ch < channels; ch++) {
      const channelData = merged.getChannelData(ch)
      let offset = 0
      for (const buf of buffers) {
        const srcChannel = Math.min(ch, buf.numberOfChannels - 1)
        channelData.set(buf.getChannelData(srcChannel), offset)
        offset += buf.length
      }
    }
    return merged
  }

  _base64ToArrayBuffer(base64) {
    const binaryStr = atob(base64)
    const bytes = new Uint8Array(binaryStr.length)
    for (let i = 0; i < binaryStr.length; i++) {
      bytes[i] = binaryStr.charCodeAt(i)
    }
    return bytes.buffer
  }

  stop() {
    this._pendingChunks = []
    this._playQueue = []
    if (this._collectTimer) {
      clearTimeout(this._collectTimer)
      this._collectTimer = null
    }
    this._isPlaying = false
    if (this._currentSource) {
      try { this._currentSource.stop() } catch (e) { /* ignore */ }
      this._currentSource = null
    }
    this.onPlayEnd?.()
  }

  destroy() {
    this.stop()
    if (this.audioContext) {
      this.audioContext.close()
      this.audioContext = null
    }
    this.analyser = null
  }
}
