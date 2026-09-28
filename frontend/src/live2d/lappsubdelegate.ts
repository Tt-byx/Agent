/**
 * Canvas 子代理 — 管理单个 Canvas 的渲染管线
 */
import * as LAppDefine from './lappdefine'
import { LAppGlManager } from './lappglmanager'
import { LAppTextureManager } from './lapptexturemanager'
import { LAppView } from './lappview'
import { LAppLive2DManager } from './lapplive2dmanager'
import { LAppPal } from './lapppal'

export class LAppSubdelegate {
  private _canvas: HTMLCanvasElement = null
  private _view: LAppView = null
  private _textureManager: LAppTextureManager = null
  private _frameBuffer: WebGLFramebuffer = null
  private _glManager: LAppGlManager = null
  private _live2dManager: LAppLive2DManager = null
  private _needResize = false
  private _resizeObserver: ResizeObserver = null

  constructor() {
    this._glManager = new LAppGlManager()
    this._textureManager = new LAppTextureManager()
    this._live2dManager = new LAppLive2DManager()
    this._view = new LAppView()
  }

  public release(): void {
    if (this._resizeObserver) {
      this._resizeObserver.disconnect()
      this._resizeObserver = null
    }
    this._live2dManager?.release()
    this._view?.release()
    this._textureManager?.release()
    this._glManager?.release()
  }

  public initialize(canvas: HTMLCanvasElement): boolean {
    if (!this._glManager.initialize(canvas)) return false

    this._canvas = canvas
    this.resizeCanvas()

    this._textureManager.setGlManager(this._glManager)

    const gl = this._glManager.getGl()
    if (!this._frameBuffer) {
      this._frameBuffer = gl.getParameter(gl.FRAMEBUFFER_BINDING)
    }

    gl.enable(gl.BLEND)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)

    this._view.initialize(this)
    this._live2dManager.setOffscreenSize(this._canvas.width, this._canvas.height)
    this._live2dManager.initialize(this)

    this._resizeObserver = new ResizeObserver(() => {
      this._needResize = true
    })
    this._resizeObserver.observe(this._canvas)

    return true
  }

  public update(): void {
    const gl = this._glManager?.getGl()
    if (!gl || gl.isContextLost()) return

    if (this._needResize) {
      this.resizeCanvas()
      this._view.initialize(this)
      this._live2dManager.setOffscreenSize(this._canvas.width, this._canvas.height)
      this._needResize = false
    }

    gl.clearColor(0.0, 0.0, 0.0, 0.0) // 透明背景
    gl.enable(gl.DEPTH_TEST)
    gl.depthFunc(gl.LEQUAL)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    gl.clearDepth(1.0)
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)

    this._view.render()
  }

  public createShader(): WebGLProgram {
    const gl = this._glManager.getGl()

    const vertexShaderId = gl.createShader(gl.VERTEX_SHADER)
    gl.shaderSource(vertexShaderId,
      'precision mediump float;' +
      'attribute vec3 position;' +
      'attribute vec2 uv;' +
      'varying vec2 vuv;' +
      'void main(void) { gl_Position = vec4(position, 1.0); vuv = uv; }'
    )
    gl.compileShader(vertexShaderId)

    const fragmentShaderId = gl.createShader(gl.FRAGMENT_SHADER)
    gl.shaderSource(fragmentShaderId,
      'precision mediump float;' +
      'varying vec2 vuv;' +
      'uniform sampler2D texture;' +
      'void main(void) { gl_FragColor = texture2D(texture, vuv); }'
    )
    gl.compileShader(fragmentShaderId)

    const programId = gl.createProgram()
    gl.attachShader(programId, vertexShaderId)
    gl.attachShader(programId, fragmentShaderId)
    gl.deleteShader(vertexShaderId)
    gl.deleteShader(fragmentShaderId)
    gl.linkProgram(programId)
    gl.useProgram(programId)

    return programId
  }

  private resizeCanvas(): void {
    this._canvas.width = this._canvas.clientWidth * window.devicePixelRatio
    this._canvas.height = this._canvas.clientHeight * window.devicePixelRatio
    const gl = this._glManager.getGl()
    gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight)
  }

  public getTextureManager(): LAppTextureManager { return this._textureManager }
  public getFrameBuffer(): WebGLFramebuffer { return this._frameBuffer }
  public getCanvas(): HTMLCanvasElement { return this._canvas }
  public getGlManager(): LAppGlManager { return this._glManager }
  public getGl(): WebGLRenderingContext | WebGL2RenderingContext { return this._glManager.getGl() }
  public getLive2DManager(): LAppLive2DManager { return this._live2dManager }
}
