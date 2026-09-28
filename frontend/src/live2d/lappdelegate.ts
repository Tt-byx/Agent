/**
 * 应用主代理 — 管理 Cubism SDK 生命周期
 */
import { CubismFramework, Option } from '@framework/live2dcubismframework'
import * as LAppDefine from './lappdefine'
import { LAppPal } from './lapppal'
import { LAppSubdelegate } from './lappsubdelegate'

export class LAppDelegate {
  private static _instance: LAppDelegate | null = null

  private _cubismOption: Option = null
  private _subdelegate: LAppSubdelegate = null
  private _canvas: HTMLCanvasElement = null
  private _animationFrameId = 0

  public static getInstance(): LAppDelegate {
    if (!LAppDelegate._instance) {
      LAppDelegate._instance = new LAppDelegate()
    }
    return LAppDelegate._instance
  }

  public static releaseInstance(): void {
    if (LAppDelegate._instance) {
      LAppDelegate._instance.release()
      LAppDelegate._instance = null
    }
  }

  private constructor() {
    this._cubismOption = new Option()
  }

  /**
   * 初始化 — 传入 Vue 管理的 canvas
   */
  public initialize(canvas: HTMLCanvasElement): boolean {
    this._canvas = canvas

    // 初始化 Cubism SDK
    LAppPal.updateTime()
    this._cubismOption.loggingLevel = LAppDefine.CubismLoggingLevel
    CubismFramework.startUp(this._cubismOption)
    CubismFramework.initialize()

    // 初始化子代理
    this._subdelegate = new LAppSubdelegate()
    if (!this._subdelegate.initialize(canvas)) {
      console.error('[Live2D] 子代理初始化失败')
      return false
    }

    // 初始化 Shader
    const view = (this._subdelegate as any)._view
    if (view?.initializeShader) {
      view.initializeShader()
    }

    // 启动渲染循环
    this.run()

    return true
  }

  private run(): void {
    const loop = (): void => {
      if (!LAppDelegate._instance) return
      LAppPal.updateTime()
      this._subdelegate?.update()
      this._animationFrameId = requestAnimationFrame(loop)
    }
    loop()
  }

  private release(): void {
    if (this._animationFrameId) {
      cancelAnimationFrame(this._animationFrameId)
      this._animationFrameId = 0
    }
    this._subdelegate?.release()
    this._subdelegate = null
    CubismFramework.dispose()
  }

  /**
   * 处理触摸事件
   */
  public onTouchBegan(x: number, y: number): void {
    const canvas = this._canvas
    if (!canvas) return
    const localX = x - canvas.getBoundingClientRect().left
    const localY = y - canvas.getBoundingClientRect().top
    const view = (this._subdelegate as any)._view
    view?.onTouchesBegan(localX, localY)
  }

  public onTouchMoved(x: number, y: number): void {
    const canvas = this._canvas
    if (!canvas) return
    const rect = canvas.getBoundingClientRect()
    const localX = x - rect.left
    const localY = y - rect.top
    const view = (this._subdelegate as any)._view
    view?.onTouchesMoved(localX, localY)
  }

  public onTouchEnded(x: number, y: number): void {
    const canvas = this._canvas
    if (!canvas) return
    const localX = x - canvas.getBoundingClientRect().left
    const localY = y - canvas.getBoundingClientRect().top
    const view = (this._subdelegate as any)._view
    view?.onTouchesEnded(localX, localY)
  }

  /**
   * 获取当前模型（供外部控制口型、表情等）
   */
  public getModel() {
    return this._subdelegate?.getLive2DManager()?.getModel() || null
  }

  public getSubdelegate(): LAppSubdelegate {
    return this._subdelegate
  }
}
