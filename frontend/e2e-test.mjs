/**
 * 前端端到端联调测试（用系统 Edge，无头）。
 * 验证：登录 → 游客对话（SSE 流式）→ 管理后台各页面 → 新增页面渲染
 */
import { chromium } from 'playwright-core'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = 'http://localhost:8080'

const log = (m) => console.log(m)
const ok = (m) => console.log('  ✓ ' + m)
const bad = (m) => console.log('  ✗ ' + m)

const errors = []

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const ctx = await browser.newContext()
const page = await ctx.newPage()

page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push('[console] ' + msg.text().slice(0, 200))
})
page.on('pageerror', (e) => errors.push('[pageerror] ' + String(e).slice(0, 200)))

try {
  // ── 1. 首页 ──
  log('\n=== 1. 首页加载 ===')
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 20000 })
  await page.waitForTimeout(1500)
  const title = await page.title()
  ok('页面标题: ' + title)
  const bodyText = await page.textContent('body')
  ok('页面有内容: ' + (bodyText || '').trim().length + ' 字符')

  // ── 2. 登录 ──
  log('\n=== 2. 登录（admin） ===')
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded', timeout: 20000 })
  await page.waitForTimeout(1200)
  const inputs = await page.locator('input').all()
  log('  发现 ' + inputs.length + ' 个输入框')
  if (inputs.length >= 2) {
    await inputs[0].fill('admin')
    await inputs[1].fill('admin123')
    // 登录按钮文本是「进入导览」（见 LoginView 的 btnText）
    const btn = page.locator('button:has-text("进入导览")').first()
    await btn.click()
    await page.waitForTimeout(3000)
    const url = page.url()
    ok('登录后 URL: ' + url)
    const token = await page.evaluate(() => localStorage.getItem('token'))
    if (token) ok('token 已写入 localStorage: ' + token.slice(0, 20) + '...')
    else bad('token 未写入')
  } else {
    bad('登录页输入框数量异常')
  }

  // ── 3. 管理后台各页面 ──
  log('\n=== 3. 管理后台页面渲染 ===')
  const adminPages = [
    ['/admin/dashboard', '数据总览'],
    ['/admin/knowledge', '知识库'],
    ['/admin/conversations', '对话记录'],
    ['/admin/ai-assistant', 'AI助手'],
    ['/admin/ops', '成本与性能'],
    ['/admin/rag-console', 'RAG 运维台'],
    ['/admin/system', '系统管理'],
  ]
  for (const [path, name] of adminPages) {
    try {
      await page.goto(BASE + path, { waitUntil: 'domcontentloaded', timeout: 20000 })
      await page.waitForTimeout(2000)
      const txt = (await page.textContent('body')) || ''
      const hasContent = txt.length > 200
      if (hasContent) ok(`${name} (${path}) 渲染 ${txt.length} 字符`)
      else bad(`${name} (${path}) 内容过少: ${txt.slice(0, 100)}`)
    } catch (e) {
      bad(`${name} (${path}) 失败: ${e.message.slice(0, 150)}`)
    }
  }

  // ── 4. 游客对话页（SSE） ──
  log('\n=== 4. 游客对话页（SSE 流式） ===')
  await page.goto(BASE + '/chat', { waitUntil: 'domcontentloaded', timeout: 20000 })
  await page.waitForTimeout(2000)
  const chatTxt = (await page.textContent('body')) || ''
  ok('对话页渲染 ' + chatTxt.length + ' 字符')

  // 找输入框并发送
  const ta = page.locator('textarea').first()
  if (await ta.count() > 0) {
    await ta.fill('灵山大佛多高')
    await page.waitForTimeout(300)
    const sendBtn = page.locator('button:has-text("发送")').first()
    if (await sendBtn.count() > 0) {
      await sendBtn.click()
      log('  已点击发送，等待流式响应...')
      // 等待回答出现（最多 30 秒）
      for (let i = 0; i < 30; i++) {
        await page.waitForTimeout(1000)
        const t = (await page.textContent('body')) || ''
        if (t.includes('88') || t.includes('米')) {
          ok(`第 ${i + 1} 秒收到回答内容`)
          break
        }
      }
      const finalTxt = (await page.textContent('body')) || ''
      const snippet = finalTxt.match(/灵山大佛[\s\S]{0,120}/)
      if (snippet) ok('回答片段: ' + snippet[0].replace(/\s+/g, ' ').slice(0, 100))
      else bad('未找到回答内容')
    } else bad('未找到发送按钮')
  } else bad('未找到输入框')

} catch (e) {
  bad('测试异常: ' + e.message)
} finally {
  log('\n=== 控制台错误汇总 ===')
  if (errors.length === 0) ok('无控制台错误')
  else errors.slice(0, 15).forEach((e) => bad(e))
  await browser.close()
}
