/**
 * 纹理管理器 — 加载 PNG 纹理
 */
import type { LAppGlManager } from './lappglmanager'

export interface TextureInfo {
  id: WebGLTexture
  width: number
  height: number
}

export class LAppTextureManager {
  private _glManager: LAppGlManager | null = null
  private _textures: Map<string, TextureInfo> = new Map()

  public setGlManager(glManager: LAppGlManager): void {
    this._glManager = glManager
  }

  public release(): void {
    if (this._glManager) {
      const gl = this._glManager.getGl()
      this._textures.forEach((info) => {
        gl.deleteTexture(info.id)
      })
    }
    this._textures.clear()
  }

  public createTextureFromPngFile(
    fileName: string,
    usePremultiply: boolean,
    callback: (textureInfo: TextureInfo) => void
  ): void {
    const gl = this._glManager.getGl()

    // 创建 1x1 透明占位纹理
    const placeholder = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, placeholder)
    gl.texImage2D(
      gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0,
      gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 0])
    )

    const textureInfo: TextureInfo = { id: placeholder, width: 1, height: 1 }
    this._textures.set(fileName, textureInfo)

    // 异步加载图片
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      gl.bindTexture(gl.TEXTURE_2D, placeholder)

      if (usePremultiply) {
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 1)
      }

      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img)

      // 生成 mipmap
      if (this._isPowerOf2(img.width) && this._isPowerOf2(img.height)) {
        gl.generateMipmap(gl.TEXTURE_2D)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR)
      } else {
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
      }
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)

      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)

      textureInfo.width = img.width
      textureInfo.height = img.height

      callback(textureInfo)
    }
    img.onerror = () => {
      console.error(`[Live2D] 纹理加载失败: ${fileName}`)
      callback(textureInfo)
    }
    img.src = fileName
  }

  private _isPowerOf2(value: number): boolean {
    return (value & (value - 1)) === 0 && value !== 0
  }
}
