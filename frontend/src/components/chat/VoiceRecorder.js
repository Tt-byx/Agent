/**
 * 语音录音工具类
 * 使用 AudioContext 录制 PCM 数据，输出 WAV 格式
 * WAV 格式可直接被 FunASR 识别，无需后端转码
 */
export class VoiceRecorder {
  constructor() {
    this.audioContext = null
    this.stream = null
    this.source = null
    this.processor = null
    this.pcmChunks = []
    this.isRecording = false
    this.startTime = 0
    this.sampleRate = 16000
  }

  static isSupported() {
    return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && (window.AudioContext || window.webkitAudioContext))
  }

  async start() {
    if (this.isRecording) return

    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        }
      })

      const AudioCtx = window.AudioContext || window.webkitAudioContext
      this.audioContext = new AudioContext({ sampleRate: this.sampleRate })
      this.source = this.audioContext.createMediaStreamSource(this.stream)

      // ScriptProcessorNode: 4096 samples per buffer, 1 channel
      this.processor = this.audioContext.createScriptProcessor(4096, 1, 1)
      this.pcmChunks = []

      this.processor.onaudioprocess = (e) => {
        if (!this.isRecording) return
        const data = e.inputBuffer.getChannelData(0)
        // 复制 Float32 数据
        this.pcmChunks.push(new Float32Array(data))
      }

      this.source.connect(this.processor)
      this.processor.connect(this.audioContext.destination)

      this.isRecording = true
      this.startTime = Date.now()

    } catch (error) {
      this._cleanup()
      if (error.name === 'NotAllowedError') {
        throw new Error('请允许麦克风权限后重试')
      } else if (error.name === 'NotFoundError') {
        throw new Error('未检测到麦克风设备')
      }
      throw error
    }
  }

  stop() {
    return new Promise((resolve) => {
      if (!this.isRecording) {
        resolve(null)
        return
      }

      const duration = Date.now() - this.startTime
      this.isRecording = false

      // 合并所有 PCM 数据
      const totalLength = this.pcmChunks.reduce((sum, c) => sum + c.length, 0)
      const pcmData = new Float32Array(totalLength)
      let offset = 0
      for (const chunk of this.pcmChunks) {
        pcmData.set(chunk, offset)
        offset += chunk.length
      }

      this._cleanup()

      // 编码为 WAV
      const wavBlob = this._encodeWav(pcmData, this.sampleRate)
      resolve({ blob: wavBlob, duration, format: 'wav' })
    })
  }

  cancel() {
    this.isRecording = false
    this._cleanup()
  }

  _cleanup() {
    if (this.processor) {
      try { this.processor.disconnect() } catch (_) {}
      this.processor = null
    }
    if (this.source) {
      try { this.source.disconnect() } catch (_) {}
      this.source = null
    }
    if (this.audioContext) {
      try { this.audioContext.close() } catch (_) {}
      this.audioContext = null
    }
    if (this.stream) {
      this.stream.getTracks().forEach(t => t.stop())
      this.stream = null
    }
    this.pcmChunks = []
  }

  _encodeWav(samples, sampleRate) {
    const numChannels = 1
    const bitsPerSample = 16
    const byteRate = sampleRate * numChannels * (bitsPerSample / 8)
    const blockAlign = numChannels * (bitsPerSample / 8)
    const dataSize = samples.length * (bitsPerSample / 8)
    const buffer = new ArrayBuffer(44 + dataSize)
    const view = new DataView(buffer)

    // RIFF header
    this._writeStr(view, 0, 'RIFF')
    view.setUint32(4, 36 + dataSize, true)
    this._writeStr(view, 8, 'WAVE')

    // fmt chunk
    this._writeStr(view, 12, 'fmt ')
    view.setUint32(16, 16, true)          // chunk size
    view.setUint16(20, 1, true)           // PCM
    view.setUint16(22, numChannels, true)
    view.setUint32(24, sampleRate, true)
    view.setUint32(28, byteRate, true)
    view.setUint16(32, blockAlign, true)
    view.setUint16(34, bitsPerSample, true)

    // data chunk
    this._writeStr(view, 36, 'data')
    view.setUint32(40, dataSize, true)

    // PCM samples (Float32 → Int16)
    let pos = 44
    for (let i = 0; i < samples.length; i++) {
      let s = Math.max(-1, Math.min(1, samples[i]))
      view.setInt16(pos, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
      pos += 2
    }

    return new Blob([buffer], { type: 'audio/wav' })
  }

  _writeStr(view, offset, str) {
    for (let i = 0; i < str.length; i++) {
      view.setUint8(offset + i, str.charCodeAt(i))
    }
  }

  destroy() {
    this.cancel()
  }
}
