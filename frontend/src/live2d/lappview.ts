/**
 * 视图管理 — 投影矩阵与渲染
 */
import { CubismMatrix44 } from '@framework/math/cubismmatrix44'
import { CubismViewMatrix } from '@framework/math/cubismviewmatrix'
import * as LAppDefine from './lappdefine'
import type { LAppSubdelegate } from './lappsubdelegate'
import { TouchManager } from './touchmanager'

export class LAppView {
  private _programId: WebGLProgram = null
  private _touchManager: TouchManager = null
  private _deviceToScreen: CubismMatrix44 = null
  private _viewMatrix: CubismViewMatrix = null
  private _subdelegate: LAppSubdelegate = null

  constructor() {
    this._touchManager = new TouchManager()
    this._deviceToScreen = new CubismMatrix44()
    this._viewMatrix = new CubismViewMatrix()
  }

  public initialize(subdelegate: LAppSubdelegate): void {
    this._subdelegate = subdelegate
    const { width, height } = subdelegate.getCanvas()

    const ratio = width / height
    const left = -ratio
    const right = ratio
    const bottom = LAppDefine.ViewLogicalLeft
    const top = LAppDefine.ViewLogicalRight

    this._viewMatrix.setScreenRect(left, right, bottom, top)
    this._viewMatrix.scale(LAppDefine.ViewScale, LAppDefine.ViewScale)

    this._deviceToScreen.loadIdentity()
    if (width > height) {
      const screenW = Math.abs(right - left)
      this._deviceToScreen.scaleRelative(screenW / width, -screenW / width)
    } else {
      const screenH = Math.abs(top - bottom)
      this._deviceToScreen.scaleRelative(screenH / height, -screenH / height)
    }
    this._deviceToScreen.translateRelative(-width * 0.5, -height * 0.5)

    this._viewMatrix.setMaxScale(LAppDefine.ViewMaxScale)
    this._viewMatrix.setMinScale(LAppDefine.ViewMinScale)
    this._viewMatrix.setMaxScreenRect(
      LAppDefine.ViewLogicalMaxLeft,
      LAppDefine.ViewLogicalMaxRight,
      LAppDefine.ViewLogicalMaxBottom,
      LAppDefine.ViewLogicalMaxTop
    )
  }

  public release(): void {
    if (this._subdelegate && this._programId) {
      this._subdelegate.getGl().deleteProgram(this._programId)
    }
    this._programId = null
  }

  public render(): void {
    const gl = this._subdelegate?.getGl()
    if (!gl) return

    gl.useProgram(this._programId)
    gl.flush()

    const manager = this._subdelegate.getLive2DManager()
    if (manager) {
      manager.setViewMatrix(this._viewMatrix)
      manager.onUpdate()
    }
  }

  /**
   * 初始化 Shader
   */
  public initializeShader(): void {
    if (!this._programId) {
      this._programId = this._subdelegate.createShader()
    }
  }

  public onTouchesBegan(x: number, y: number): void {
    this._touchManager.touchesBegan(x * window.devicePixelRatio, y * window.devicePixelRatio)
  }

  public onTouchesMoved(x: number, y: number): void {
    this._touchManager.touchesMoved(x * window.devicePixelRatio, y * window.devicePixelRatio)
    const manager = this._subdelegate?.getLive2DManager()
    const viewX = this._deviceToScreen.transformX(this._touchManager.getX())
    const viewY = this._deviceToScreen.transformY(this._touchManager.getY())
    manager?.onDrag(viewX, viewY)
  }

  public onTouchesEnded(x: number, y: number): void {
    const manager = this._subdelegate?.getLive2DManager()
    manager?.onDrag(0, 0)

    const posX = x * window.devicePixelRatio
    const posY = y * window.devicePixelRatio
    const viewX = this.transformViewX(posX)
    const viewY = this.transformViewY(posY)
    manager?.onTap(viewX, viewY)
  }

  public transformViewX(deviceX: number): number {
    return this._viewMatrix.invertTransformX(this._deviceToScreen.transformX(deviceX))
  }

  public transformViewY(deviceY: number): number {
    return this._viewMatrix.invertTransformY(this._deviceToScreen.transformY(deviceY))
  }
}
