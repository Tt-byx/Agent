/**
 * 音频参数提供器 — 用于驱动 LipSync + MouthForm
 */
import { IParameterProvider } from '@framework/motion/iparameterprovider'

export class AudioParameterProvider extends IParameterProvider {
  private _value = 0
  private _targetValue = 0
  private _mouthFormValue = 0
  private _targetMouthFormValue = 0

  constructor() {
    super()
  }

  /**
   * 设置目标口型张开值 (0.0 ~ 1.0)
   */
  public setLipSyncValue(value: number): void {
    this._targetValue = Math.max(0, Math.min(1, value))
  }

  /**
   * 设置目标口型形状 (-1.0 ~ 1.0)
   * -1 = 嘟嘴(i/u), 0 = 自然, 1 = 咧嘴(a/e)
   */
  public setMouthFormValue(value: number): void {
    this._targetMouthFormValue = Math.max(-1, Math.min(1, value))
  }

  public update(deltaTimeSeconds?: number): boolean {
    const speed = 12.0
    const dt = deltaTimeSeconds ?? 0.016
    const factor = Math.min(1, speed * dt)
    this._value += (this._targetValue - this._value) * factor
    this._mouthFormValue += (this._targetMouthFormValue - this._mouthFormValue) * factor
    return true
  }

  public getParameter(): number {
    return this._value
  }

  /**
   * 获取口型形状值（供多参数 lip sync 使用）
   */
  public getMouthFormParameter(): number {
    return this._mouthFormValue
  }
}