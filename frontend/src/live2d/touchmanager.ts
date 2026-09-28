/**
 * 触摸/鼠标事件管理
 */
export class TouchManager {
  private _startX = 0
  private _startY = 0
  private _lastX = 0
  private _lastY = 0
  private _lastX1 = 0
  private _lastY1 = 0
  private _lastX2 = 0
  private _lastY2 = 0
  private _lastTouchDistance = 0
  private _touchSingle = false

  public touchesBegan(x: number, y: number): void {
    this._lastX = x
    this._lastY = y
    this._startX = x
    this._startY = y
    this._lastTouchDistance = -1
    this._touchSingle = true
  }

  public touchesMoved(x: number, y: number): void {
    this._lastX = x
    this._lastY = y
  }

  public getX(): number {
    return this._lastX
  }

  public getY(): number {
    return this._lastY
  }

  public getStartX(): number {
    return this._startX
  }

  public getStartY(): number {
    return this._startY
  }
}
