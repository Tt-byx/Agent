/**
 * 精灵渲染（用于背景等 2D 图层）
 */
import type { LAppSubdelegate } from './lappsubdelegate'

export class LAppSprite {
  private _textureId: WebGLTexture
  private _vertexBuffer: WebGLBuffer
  private _uvBuffer: WebGLBuffer
  private _indexBuffer: WebGLBuffer
  private _subdelegate: LAppSubdelegate | null = null

  constructor(
    x: number,
    y: number,
    width: number,
    height: number,
    textureId: WebGLTexture
  ) {
    this._textureId = textureId

    const halfW = width * 0.5
    const halfH = height * 0.5

    // 顶点坐标（会由 render 时动态计算）
    this._vertexBuffer = null
    this._uvBuffer = null
    this._indexBuffer = null
  }

  public setSubdelegate(subdelegate: LAppSubdelegate): void {
    this._subdelegate = subdelegate
  }

  public render(programId: WebGLProgram): void {
    // 简化实现 — 在我们的集成中不渲染背景精灵
  }

  public release(): void {
    if (this._subdelegate) {
      const gl = this._subdelegate.getGl()
      if (this._vertexBuffer) gl.deleteBuffer(this._vertexBuffer)
      if (this._uvBuffer) gl.deleteBuffer(this._uvBuffer)
      if (this._indexBuffer) gl.deleteBuffer(this._indexBuffer)
    }
  }

  public isHit(x: number, y: number): boolean {
    return false
  }
}
