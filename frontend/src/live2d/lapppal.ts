/**
 * 平台抽象层 — 时间管理与日志输出
 */

let s_currentTime = 0.0
let s_lastTime = 0.0
let s_deltaTime = 0.0

export class LAppPal {
  public static updateTime(): void {
    s_currentTime = performance.now() / 1000.0
    s_deltaTime = s_currentTime - s_lastTime
    s_lastTime = s_currentTime
  }

  public static getDeltaTime(): number {
    return s_deltaTime
  }

  public static printMessage(message: string): void {
    console.log('[Live2D]', message)
  }
}
