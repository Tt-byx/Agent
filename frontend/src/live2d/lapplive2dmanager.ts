/**
 * Live2D 模型管理器 — 管理模型生命周期
 */
import { CubismMatrix44 } from '@framework/math/cubismmatrix44'
import { CubismWebGLOffscreenManager } from '@framework/rendering/cubismoffscreenmanager'
import * as LAppDefine from './lappdefine'
import { LAppModel } from './lappmodel'
import type { LAppSubdelegate } from './lappsubdelegate'

export class LAppLive2DManager {
  private _subdelegate: LAppSubdelegate = null
  private _viewMatrix: CubismMatrix44 = new CubismMatrix44()
  private _models: LAppModel[] = []
  private _sceneIndex = 0
  private _swayTime = 0

  constructor() {}

  public release(): void {
    this._models.length = 0
  }

  public setOffscreenSize(width: number, height: number): void {
    for (const model of this._models) {
      model?.setRenderTargetSize(width, height)
    }
  }

  public onDrag(x: number, y: number): void {
    this._models[0]?.setDragging(x, y)
  }

  public onTap(x: number, y: number): void {
    const model = this._models[0]
    if (!model) return

    if (model.hitTest(LAppDefine.HitAreaNameHead, x, y)) {
      model.setRandomExpression()
    } else if (model.hitTest(LAppDefine.HitAreaNameBody, x, y)) {
      model.setRandomExpression()
    }
  }

  public onUpdate(): void {
    const gl = this._subdelegate?.getGl()
    if (!gl) return

    CubismWebGLOffscreenManager.getInstance().beginFrameProcess(gl)

    const { width, height } = this._subdelegate.getCanvas()
    const projection = new CubismMatrix44()
    const model = this._models[0]

    if (model?.getModel()) {
      if (model.getModel().getCanvasWidth() > 1.0 && width < height) {
        model.getModelMatrix().setWidth(2.0)
        projection.scale(1.0, width / height)
      } else {
        projection.scale(height / width, 1.0)
      }

      if (this._viewMatrix) {
        projection.multiplyByMatrix(this._viewMatrix)
      }
    }

    model?.update()

    // 微微抖动：正弦波缓慢位移
    this._swayTime += 0.016
    const swayX = Math.sin(this._swayTime * 0.5) * 0.003
    const swayY = Math.cos(this._swayTime * 0.7) * 0.002
    const arr = projection.getArray()
    arr[4] += swayX
    arr[5] += swayY

    model?.draw(projection)

    CubismWebGLOffscreenManager.getInstance().endFrameProcess(gl)
    CubismWebGLOffscreenManager.getInstance().releaseStaleRenderTextures(gl)
  }

  public setViewMatrix(m: CubismMatrix44): void {
    for (let i = 0; i < 16; i++) {
      this._viewMatrix.getArray()[i] = m.getArray()[i]
    }
  }

  public initialize(subdelegate: LAppSubdelegate): void {
    this._subdelegate = subdelegate
    this.changeScene(this._sceneIndex)
  }

  private changeScene(index: number): void {
    this._sceneIndex = index
    const model = LAppDefine.ModelDir[index]
    const modelPath = LAppDefine.ResourcesPath + model + '/'
    const modelJsonName = model + '.model3.json'

    this._models.length = 0
    const instance = new LAppModel()
    instance.setSubdelegate(this._subdelegate)
    instance.loadAssets(modelPath, modelJsonName)
    this._models.push(instance)
  }

  /**
   * 获取当前模型（供外部控制）
   */
  public getModel(): LAppModel | null {
    return this._models[0] || null
  }
}
