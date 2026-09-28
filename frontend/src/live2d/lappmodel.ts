/**
 * Live2D ģ�ͼ�������� �� SDK v5 ����
 */
import { CubismDefaultParameterId } from '@framework/cubismdefaultparameterid'
import { CubismModelSettingJson } from '@framework/cubismmodelsettingjson'
import { BreathParameterData, CubismBreath } from '@framework/effect/cubismbreath'
import { CubismEyeBlink } from '@framework/effect/cubismeyeblink'
import { CubismLook, LookParameterData } from '@framework/effect/cubismlook'
import { CubismPose } from '@framework/effect/cubismpose'
import { ICubismModelSetting } from '@framework/icubismmodelsetting'
import { CubismIdHandle } from '@framework/id/cubismid'
import { CubismFramework } from '@framework/live2dcubismframework'
import { CubismMatrix44 } from '@framework/math/cubismmatrix44'
import { CubismUserModel } from '@framework/model/cubismusermodel'
import { ACubismMotion, BeganMotionCallback, FinishedMotionCallback } from '@framework/motion/acubismmotion'
import { CubismMotion } from '@framework/motion/cubismmotion'
import { CubismMotionQueueEntryHandle, InvalidMotionQueueEntryHandleValue } from '@framework/motion/cubismmotionqueuemanager'
import { CubismUpdateScheduler } from '@framework/motion/cubismupdatescheduler'
import { CubismBreathUpdater } from '@framework/motion/cubismbreathupdater'
import { CubismLookUpdater } from '@framework/motion/cubismlookupdater'
import { CubismEyeBlinkUpdater } from '@framework/motion/cubismeyeblinkupdater'
import { CubismExpressionUpdater } from '@framework/motion/cubismexpressionupdater'
import { CubismPhysicsUpdater } from '@framework/motion/cubismphysicsupdater'
import { CubismPoseUpdater } from '@framework/motion/cubismposeupdater'
import { CubismLipSyncUpdater } from '@framework/motion/cubismlipsyncupdater'
import { csmRect } from '@framework/type/csmrectf'
import { CubismMoc } from '@framework/model/cubismmoc'

import * as LAppDefine from './lappdefine'
import { LAppPal } from './lapppal'
import type { TextureInfo } from './lapptexturemanager'
import type { LAppSubdelegate } from './lappsubdelegate'
import { AudioParameterProvider } from './audio-parameter-provider'

enum LoadStep {
  LoadAssets,
  LoadModel,
  LoadExpression,
  LoadPhysics,
  SetupUpdaters,
  LoadTexture,
  CompleteSetup,
}

export class LAppModel extends CubismUserModel {
  private _modelSetting: ICubismModelSetting = null
  private _modelHomeDir: string = null
  private _userTimeSeconds = 0.0

  private _eyeBlinkIds: Array<CubismIdHandle> = []
  private _lipSyncIds: Array<CubismIdHandle> = []

  private _motions: Map<string, ACubismMotion> = new Map()
  private _expressions: Map<string, ACubismMotion> = new Map()

  private _idParamAngleX: CubismIdHandle
  private _idParamAngleY: CubismIdHandle
  private _idParamAngleZ: CubismIdHandle
  private _idParamBodyAngleX: CubismIdHandle
  private _idParamEyeBallX: CubismIdHandle
  private _idParamEyeBallY: CubismIdHandle
  private _idParamMouthForm: CubismIdHandle
  private _idParamBodyAngleY: CubismIdHandle
  private _idParamBodyAngleZ: CubismIdHandle
  private _idParamBodyAngleX2: CubismIdHandle
  private _idParamShoulderX: CubismIdHandle

  private _state: LoadStep = LoadStep.LoadAssets
  private _textureCount = 0

  private _updateScheduler: CubismUpdateScheduler = null
  private _audioProvider: AudioParameterProvider = null

  private _subdelegate: LAppSubdelegate = null

  constructor() {
    super()

    this._idParamAngleX = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParamAngleX)
    this._idParamAngleY = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParamAngleY)
    this._idParamAngleZ = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParamAngleZ)
    this._idParamBodyAngleX = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParamBodyAngleX)
    this._idParamEyeBallX = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParamEyeBallX)
    this._idParamEyeBallY = CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParamEyeBallY)
    this._idParamMouthForm = CubismFramework.getIdManager().getId('ParamMouthForm')
    this._idParamBodyAngleY = CubismFramework.getIdManager().getId('ParamBodyAngleY')
    this._idParamBodyAngleZ = CubismFramework.getIdManager().getId('ParamBodyAngleZ')
    this._idParamBodyAngleX2 = CubismFramework.getIdManager().getId('ParamBodyAngleX2')
    this._idParamShoulderX = CubismFramework.getIdManager().getId('ParamBodyAngleX3')

    if (LAppDefine.MOCConsistencyValidationEnable) {
      this._mocConsistency = true
    }

    this._audioProvider = new AudioParameterProvider()
    this._state = LoadStep.LoadAssets
  }

  public setSubdelegate(subdelegate: LAppSubdelegate): void {
    this._subdelegate = subdelegate
  }

  public loadAssets(dir: string, fileName: string): void {
    this._modelHomeDir = dir

    fetch(`${this._modelHomeDir}${fileName}`)
      .then((res) => res.arrayBuffer())
      .then((buf) => {
        const setting = new CubismModelSettingJson(buf, buf.byteLength)
        this._modelSetting = setting
        this._state = LoadStep.LoadModel
        this._loadModel(setting)
      })
      .catch((e) => console.error(`[Live2D] ����ʧ��: ${dir}${fileName}`, e))
  }

  private _loadModel(setting: ICubismModelSetting): void {
    this._updating = true
    this._initialized = false

    const fileName = setting.getModelFileName()
    if (!fileName) return

    fetch(`${this._modelHomeDir}${fileName}`)
      .then((res) => res.arrayBuffer())
      .then((buf) => {
        this.loadModel(buf, this._mocConsistency)
        // ������Ⱦ���������ڰ�����֮ǰ��
        const canvas = this._subdelegate.getCanvas()
        this.createRenderer(canvas.width, canvas.height)
        // �� WebGL �����Ĵ��ݸ���Ⱦ��
        const gl = this._subdelegate.getGl()
        this.getRenderer().startUp(gl)
        this.setRenderTargetSize(canvas.width, canvas.height)
        this._state = LoadStep.LoadExpression
        this._loadExpressions(setting)
      })
      .catch((e) => console.error('[Live2D] moc3 ����ʧ��', e))
  }

  private _loadExpressions(setting: ICubismModelSetting): void {
    const count = setting.getExpressionCount()
    let loaded = 0

    if (count === 0) {
      this._loadPhysics(setting)
      return
    }

    for (let i = 0; i < count; i++) {
      const name = setting.getExpressionName(i)
      const file = setting.getExpressionFileName(i)

      fetch(`${this._modelHomeDir}${file}`)
        .then((res) => res.arrayBuffer())
        .then((buf) => {
          const motion = this.loadExpression(buf, buf.byteLength, name)
          if (motion) this._expressions.set(name, motion)
          loaded++
          if (loaded >= count) this._loadPhysics(setting)
        })
        .catch(() => {
          loaded++
          if (loaded >= count) this._loadPhysics(setting)
        })
    }
  }

  private _loadPhysics(setting: ICubismModelSetting): void {
    this._state = LoadStep.LoadPhysics
    const file = setting.getPhysicsFileName()

    if (!file) {
      this._setupUpdaters(setting)
      return
    }

    fetch(`${this._modelHomeDir}${file}`)
      .then((res) => res.arrayBuffer())
      .then((buf) => {
        this.loadPhysics(buf, buf.byteLength)
        this._setupUpdaters(setting)
      })
      .catch(() => this._setupUpdaters(setting))
  }

  private _setupUpdaters(setting: ICubismModelSetting): void {
    this._state = LoadStep.SetupUpdaters

    // ��ȡ EyeBlink IDs
    const eyeBlinkCount = setting.getEyeBlinkParameterCount()
    for (let i = 0; i < eyeBlinkCount; i++) {
      this._eyeBlinkIds.push(setting.getEyeBlinkParameterId(i))
    }

    // ��ȡ LipSync IDs
    const lipSyncCount = setting.getLipSyncParameterCount()
    for (let i = 0; i < lipSyncCount; i++) {
      this._lipSyncIds.push(setting.getLipSyncParameterId(i))
    }

    // Pose
    const poseFile = setting.getPoseFileName()
    if (poseFile) {
      fetch(`${this._modelHomeDir}${poseFile}`)
        .then((res) => res.arrayBuffer())
        .then((buf) => {
          this.loadPose(buf, buf.byteLength)
          this._buildScheduler(setting)
          this._loadTextures(setting)
        })
        .catch(() => {
          this._buildScheduler(setting)
          this._loadTextures(setting)
        })
    } else {
      this._buildScheduler(setting)
      this._loadTextures(setting)
    }
  }

  private _buildScheduler(setting: ICubismModelSetting): void {
    this._updateScheduler = new CubismUpdateScheduler()

    // motionUpdated �ص� �� Ĭ�Ϸ��� false���޶�������ʱ����գ�ۣ�
    const motionUpdated = () => false

    // EyeBlink
    if (this._eyeBlink) {
      CubismEyeBlink.delete(this._eyeBlink)
    }
    this._eyeBlink = CubismEyeBlink.create(setting)
    if (this._eyeBlink) {
      this._updateScheduler.addUpdatableList(
        new CubismEyeBlinkUpdater(motionUpdated, this._eyeBlink)
      )
    }

    // Expression
    this._updateScheduler.addUpdatableList(
      new CubismExpressionUpdater(this._expressionManager)
    )

    // Look (drag) — 注视追踪
    if (!this._look) {
      this._look = CubismLook.create()
    }
    this._look.setParameters([
      new LookParameterData(this._idParamEyeBallX, 1.5, 0, 0),
      new LookParameterData(this._idParamEyeBallY, 0, 1.5, 0),
      new LookParameterData(this._idParamAngleX, 8, 0, 0),
      new LookParameterData(this._idParamAngleY, 0, 8, 0),
      new LookParameterData(this._idParamAngleZ, 0.5, 2, 0),
      new LookParameterData(this._idParamBodyAngleX, 4, 0, 0),
      new LookParameterData(this._idParamBodyAngleY, 0, 2, 0),
      new LookParameterData(this._idParamBodyAngleX2, 0.5, 0.5, 0),
      new LookParameterData(this._idParamShoulderX, 1, 0, 0),
    ])
    this._updateScheduler.addUpdatableList(
      new CubismLookUpdater(this._look, this._dragManager)
    )

    // Breath
    if (!this._breath) {
      this._breath = CubismBreath.create()
    }
    this._breath.setParameters([
      new BreathParameterData(this._idParamAngleX, 0, 0.5, 3.765, 0.5),
      new BreathParameterData(this._idParamAngleY, 0.3, 0.4, 5.124, 0.5),
      new BreathParameterData(this._idParamAngleZ, -0.2, 0.3, 4.231, 0.5),
      new BreathParameterData(this._idParamBodyAngleX, 0, 0.8, 3.765, 0.5),
      new BreathParameterData(this._idParamBodyAngleY, 0.2, 0.3, 4.897, 0.5),
      new BreathParameterData(this._idParamBodyAngleZ, -0.1, 0.2, 5.543, 0.5),
      new BreathParameterData(this._idParamShoulderX, 0, 0.3, 3.765, 0.5),
      new BreathParameterData(this._idParamBodyAngleX2, 0, 0.15, 6.123, 0.5),
    ])
    this._updateScheduler.addUpdatableList(
      new CubismBreathUpdater(this._breath)
    )

    // Physics
    if (this._physics) {
      this._updateScheduler.addUpdatableList(
        new CubismPhysicsUpdater(this._physics)
      )
    }

    // LipSync — bind mouth parameters
    if (this._lipSyncIds.length === 0) {
      const mouthOpenId = CubismFramework.getIdManager().getId('ParamMouthOpenY')
      const mouthFormId = CubismFramework.getIdManager().getId('ParamMouthForm')
      const mouthXId = CubismFramework.getIdManager().getId('Param83') // ANIYA mouthX
      if (mouthOpenId) this._lipSyncIds.push(mouthOpenId)
      if (mouthFormId) this._lipSyncIds.push(mouthFormId)
      if (mouthXId) this._lipSyncIds.push(mouthXId)
    }
    this._updateScheduler.addUpdatableList(
      new CubismLipSyncUpdater(this._lipSyncIds, this._audioProvider)
    )

    // Pose
    if (this._pose) {
      this._updateScheduler.addUpdatableList(
        new CubismPoseUpdater(this._pose)
      )
    }

    // Layout
    const layoutMap = new Map<string, number>()
    if (setting.getLayoutMap(layoutMap)) {
      if (layoutMap.has('Width')) this._modelMatrix.setWidth(layoutMap.get('Width'))
      if (layoutMap.has('Height')) this._modelMatrix.setHeight(layoutMap.get('Height'))
      if (layoutMap.has('X')) this._modelMatrix.setX(layoutMap.get('X'))
      if (layoutMap.has('Y')) this._modelMatrix.setY(layoutMap.get('Y'))
    }
  }

  private _loadTextures(setting: ICubismModelSetting): void {
    this._state = LoadStep.LoadTexture
    this._textureCount = setting.getTextureCount()

    if (this._textureCount === 0) {
      this._state = LoadStep.CompleteSetup
      this._initialized = true
      this._updating = false
      return
    }

    for (let i = 0; i < this._textureCount; i++) {
      const texFile = setting.getTextureFileName(i)
      this._subdelegate.getTextureManager().createTextureFromPngFile(
        `${this._modelHomeDir}${texFile}`,
        true,
        (textureInfo: TextureInfo) => {
          this.getRenderer().bindTexture(i, textureInfo.id)
          this._textureCount--
          if (this._textureCount <= 0) {
            this._state = LoadStep.CompleteSetup
            this._initialized = true
            this._updating = false
          }
        }
      )
    }
  }

  /**
   * ÿ֡����
   */
  public update(): void {
    if (this._state !== LoadStep.CompleteSetup) return

    const dt = LAppPal.getDeltaTime()
    this._userTimeSeconds += dt

    // �������׷��
    const dragX = this._dragManager.getX()
    const dragY = this._dragManager.getY()

    // ͨ����������������Ч��
    if (this._updateScheduler && this._model) {
      this._updateScheduler.onLateUpdate(this._model, dt)
      // Apply mouth form from audio provider (separate from open/close)
      if (this._audioProvider && this._idParamMouthForm) {
        this._model.addParameterValueById(this._idParamMouthForm, this._audioProvider.getMouthFormParameter())
      }
      // Apply parameter changes to model mesh
      this._model.update()
    }
  }

  /**
   * ��Ⱦģ��
   */
  public draw(matrix: CubismMatrix44): void {
    if (!this._model || this._state !== LoadStep.CompleteSetup) return

    matrix.multiplyByMatrix(this._modelMatrix)
    this.getRenderer().setMvpMatrix(matrix)
    this.getRenderer().drawModel(LAppDefine.ShaderPath)
  }

  /**
   * ���ÿ���ͬ��ֵ (0.0 ~ 1.0)
   */
  public setLipSyncValue(value: number): void {
    if (this._audioProvider) {
      this._audioProvider.setLipSyncValue(value)
    }
    if (this._model) {
      const v = Math.max(0, Math.min(1, value))
      // Param83 = ANIYA 的主嘴巴参数（mouthX）
      this._model.setParameterValueById(
        CubismFramework.getIdManager().getId('Param83'), v
      )
      // ParamMouthOpenY 也设（兼容其他模型）
      this._model.setParameterValueById(
        CubismFramework.getIdManager().getId('ParamMouthOpenY'), v
      )
    }
  }

  /**
   * 设置口型形状 (-1.0 ~ 1.0)
   * -1 = 嘟嘴(i/u), 0 = 自然, 1 = 咧嘴(a/e)
   */
  public setMouthForm(value: number): void {
    if (this._audioProvider) {
      this._audioProvider.setMouthFormValue(value)
    }
  }

  /**
   * ���ñ���
   */
  public setExpressionByName(name: string): void {
    // 先清空所有旧表情动画，防止残留面部参数
    this._expressionManager.resetAll()

    const motion = this._expressions.get(name)
    if (motion) {
      this._expressionManager.startMotion(motion, false)
    }
    this._applyExpressionBodyMotion(name)
  }

  // Part 索引缓存（延迟初始化）
  private _partCache: Map<string, number> = new Map()

  private _getPartIndex(partId: string): number {
    if (this._partCache.has(partId)) return this._partCache.get(partId)
    if (!this._model) return -1
    const count = this._model.getPartCount()
    for (let i = 0; i < count; i++) {
      if (this._model.getPartId(i).getString() === partId) {
        this._partCache.set(partId, i)
        return i
      }
    }
    return -1
  }

  private _setPartVisible(partId: string, visible: boolean): void {
    const idx = this._getPartIndex(partId)
    if (idx >= 0 && this._model) {
      this._model.setPartOpacityByIndex(idx, visible ? 1.0 : 0.0)
    }
  }

  private _hideAllParts(): void {
    const parts = ['Part2','Part4','Part6','Part12','Part24','Part174','Part25','Part26','Part27','Part28']
    parts.forEach(p => this._setPartVisible(p, false))
  }

  /**
   * 表情触发配套身体动作和部件显示
   */
  private _applyExpressionBodyMotion(name: string): void {
    if (!this._model) return
    const m = this._model

    // 先重置所有身体参数到中立位，防止上一个表情的残留
    m.setParameterValueById(this._idParamBodyAngleX, 0)
    m.setParameterValueById(this._idParamBodyAngleY, 0)
    m.setParameterValueById(this._idParamBodyAngleZ, 0)
    m.setParameterValueById(this._idParamBodyAngleX2, 0)
    m.setParameterValueById(this._idParamShoulderX, 0)

    // 隐藏所有特效部件
    this._hideAllParts()

    switch (name.toLowerCase()) {
      case 'smile':
      case 'happy':
        m.addParameterValueById(this._idParamBodyAngleY, -0.5, 0)
        m.addParameterValueById(this._idParamBodyAngleX2, 0.3, 0)
        break
      case 'angry':
        m.addParameterValueById(this._idParamBodyAngleX, -1.5, 0)
        m.addParameterValueById(this._idParamShoulderX, 0.5, 0)
        m.addParameterValueById(this._idParamBodyAngleY, 0.5, 0)
        this._setPartVisible('Part25', true)
        break
      case 'cry':
      case 'sad':
        m.addParameterValueById(this._idParamBodyAngleY, 1, 0)
        m.addParameterValueById(this._idParamBodyAngleX, 0.5, 0)
        m.addParameterValueById(this._idParamShoulderX, -0.3, 0)
        this._setPartVisible('Part26', true)
        this._setPartVisible('Part27', true)
        this._setPartVisible('Part28', true)
        break
      case 'star':
      case 'surprise':
        m.addParameterValueById(this._idParamBodyAngleY, -1, 0)
        m.addParameterValueById(this._idParamBodyAngleX2, -0.5, 0)
        this._setPartVisible('Part24', true)
        break
      case 'circle':
      case 'thinking':
        m.addParameterValueById(this._idParamBodyAngleZ, 0.5, 0)
        m.addParameterValueById(this._idParamBodyAngleX, 0.3, 0)
        this._setPartVisible('Part174', true)
        break
      case 'pucker':
        m.addParameterValueById(this._idParamBodyAngleY, -0.3, 0)
        break
      case 'chew':
        m.addParameterValueById(this._idParamBodyAngleY, -0.2, 0)
        m.addParameterValueById(this._idParamBodyAngleX2, 0.2, 0)
        break
      case 'sidemouth':
        m.addParameterValueById(this._idParamBodyAngleZ, -0.5, 0)
        m.addParameterValueById(this._idParamShoulderX, 0.3, 0)
        break
      case 'pokeface':
        this._setPartVisible('Part2', true)
        break
      case 'mic':
        this._setPartVisible('Part4', true)
        break
      case 'controller':
        this._setPartVisible('Part6', true)
        this._setPartVisible('Part12', true)
        break
      case 'normal':
        break
    }
  }

  /**
   * ����л�����
   */
  public setRandomExpression(): void {
    if (this._expressions.size === 0) return
    const names = Array.from(this._expressions.keys())
    const index = Math.floor(Math.random() * names.length)
    this.setExpressionByName(names[index])
  }

  /**
   * ע��ĳ����
   */
  public setDragging(x: number, y: number): void {
    super.setDragging(x, y)
  }

  /**
   * ���������
   */
  public hitTest(name: string, x: number, y: number): boolean {
    for (let i = 0; i < this._modelSetting.getHitAreasCount(); i++) {
      if (this._modelSetting.getHitAreaName(i) === name) {
        const drawId = this._modelSetting.getHitAreaId(i)
        return this.isHit(drawId, x, y)
      }
    }
    return false
  }

  public isReady(): boolean {
    return this._state === LoadStep.CompleteSetup
  }

  public release(): void {
    if (this._updateScheduler) {
      this._updateScheduler.release()
      this._updateScheduler = null
    }
    if (this._look) {
      CubismLook.delete(this._look)
      this._look = null
    }
    super.release()
  }
}






