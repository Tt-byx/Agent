import { LogLevel } from '@framework/live2dcubismframework'

// 模型配置
export const ResourcesPath = '/models/'
export const ModelDir: string[] = ['aniya']
export const ModelDirSize = ModelDir.length

// Shader 路径
export const ShaderPath = '/cubism/shaders/WebGL/'

// 画布
export const CanvasSize: { width: number; height: number } | 'auto' = 'auto'

// 视图缩放
export const ViewScale = 1.0
export const ViewMaxScale = 2.0
export const ViewMinScale = 0.8
export const ViewLogicalLeft = -1.0
export const ViewLogicalRight = 1.0
export const ViewLogicalBottom = -1.0
export const ViewLogicalTop = 1.0
export const ViewLogicalMaxLeft = -2.0
export const ViewLogicalMaxRight = 2.0
export const ViewLogicalMaxBottom = -2.0
export const ViewLogicalMaxTop = 2.0

// 动作优先级
export const PriorityNone = 0
export const PriorityIdle = 1
export const PriorityNormal = 2
export const PriorityForce = 3

// 动作分组
export const MotionGroupIdle = 'Idle'
export const MotionGroupTapBody = 'TapBody'

// 点击区域
export const HitAreaNameHead = 'Head'
export const HitAreaNameBody = 'Body'

// 校验
export const MOCConsistencyValidationEnable = true
export const MotionConsistencyValidationEnable = true

// 日志
export const DebugLogEnable = false
export const DebugTouchLogEnable = false
export const CubismLoggingLevel: LogLevel = LogLevel.LogLevel_Warning

// 表情映射：情绪 → 表情文件名
export const ExpressionMap: Record<string, string> = {
  happy: 'Smile',
  smile: 'Smile',
  sad: 'Cry',
  cry: 'Cry',
  angry: 'Angry',
  surprise: 'Star',
  star: 'Star',
  neutral: 'Normal',
  normal: 'Normal',
  thinking: 'Circle',
  circle: 'Circle',
  pucker: 'Pucker',
  chew: 'Chew',
  side: 'SideMouth',
  sidemouth: 'SideMouth',
  pokeface: 'PokeFace',
}
