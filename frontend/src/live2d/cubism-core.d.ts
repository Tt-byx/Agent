/**
 * Live2D Cubism Core 类型声明
 * Core 通过 <script> 标签加载为全局变量
 */
declare namespace Live2DCubismCore {
  namespace Logging {
    function csmSetLogFunction(logFn: (message: string) => void): void
    function csmGetLogFunction(): ((message: string) => void) | null
  }
  namespace Version {
    function csmGetVersion(): number
  }
  namespace Memory {
    function initializeAmountOfMemory(size: number): void
  }
}
