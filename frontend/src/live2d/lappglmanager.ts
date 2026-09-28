/**
 * WebGL 上下文管理
 */
export class LAppGlManager {
  private _gl: WebGLRenderingContext | WebGL2RenderingContext = null

  public initialize(canvas: HTMLCanvasElement): boolean {
    this._gl = canvas.getContext('webgl2', {
      premultipliedAlpha: true,
      alpha: true,
      preserveDrawingBuffer: true,
    })
    if (!this._gl) {
      this._gl = canvas.getContext('webgl', {
        premultipliedAlpha: true,
        alpha: true,
        preserveDrawingBuffer: true,
      })
    }
    if (!this._gl) {
      console.error('[Live2D] WebGL 初始化失败')
      return false
    }
    return true
  }

  public release(): void {
    this._gl = null
  }

  public getGl(): WebGLRenderingContext | WebGL2RenderingContext {
    return this._gl
  }
}
