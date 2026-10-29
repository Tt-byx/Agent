/**
 * 针对性联调验证：SSE 身份传递修复。
 *
 * 复现场景（修复前的问题）：
 *   admin 登录 → 进入 /chat（自动载入自己的历史会话）→ 发消息
 *   → 后端 SSE 工作线程身份丢失 → 判定「会话不属于当前账号」→ 红条「服务内部错误」
 *
 * 修复后期望：
 *   同一流程能正常流式出字；页面不出现错误提示；且新会话归属为 admin。
 */
import { chromium } from 'playwright-core'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = 'http://localhost:8080'
const OUT = 'C:\\Users\\28182\\Desktop\\Agent\\frontend\\shots'

const ok = (m) => console.log('  ✓ ' + m)
const bad = (m) => console.log('  ✗ ' + m)

const errors = []
const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } })
const page = await ctx.newPage()

page.on('console', (m) => { if (m.type() === 'error') errors.push('[console] ' + m.text().slice(0, 200)) })
page.on('pageerror', (e) => errors.push('[pageerror] ' + String(e).slice(0, 200)))

// 记录所有 chat/stream 请求的响应状态
const streamStatus = []
page.on('response', (r) => {
  if (r.url().includes('/api/chat/stream')) streamStatus.push(r.status())
})

console.log('\n=== 1. admin 登录 ===')
await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)
const inputs = await page.locator('input').all()
await inputs[0].fill('admin')
await inputs[1].fill('admin123')
await page.locator('button:has-text("进入导览")').first().click()
await page.waitForTimeout(3000)
ok('登录后 URL: ' + page.url())

console.log('\n=== 2. 进入聊天页（会载入 admin 的历史会话）===')
await page.goto(BASE + '/chat', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2500)

// 看看 store 里载入的 sessionId 属于谁
const loadedSession = await page.evaluate(() => {
  // Pinia 状态挂载在 app 上不易直接取，这里从最近对话接口反推：读 DOM 历史项
  return document.querySelector('.history-item.active .conv-title')?.textContent?.trim() || null
})
console.log('  载入的历史会话标题: ' + (loadedSession || '(无)'))

console.log('\n=== 3. 发一条消息（关键：修复前这里会出「服务内部错误」）===')
const ta = page.locator('textarea').first()
await ta.fill('灵山大佛多高')
await page.waitForTimeout(300)
await page.locator('button:has-text("发送")').first().click()

// 等流式完成
let gotAnswer = false
for (let i = 0; i < 45; i++) {
  await page.waitForTimeout(1000)
  const t = (await page.textContent('body')) || ''
  if (t.includes('88') || (t.includes('米') && t.includes('灵山'))) { gotAnswer = true; console.log(`  第 ${i + 1} 秒收到回答`); break }
}
await page.waitForTimeout(1500)

const bodyTxt = (await page.textContent('body')) || ''
console.log('\n=== 4. 断言 ===')
gotAnswer ? ok('收到模型回答内容') : bad('未收到回答内容')

// 关键断言：不出现「服务内部错误」
if (bodyTxt.includes('服务内部错误')) bad('页面仍出现「服务内部错误」！')
else ok('页面无「服务内部错误」')

if (bodyTxt.includes('该会话不属于当前账号')) bad('页面出现归属错误提示')
else ok('页面无归属错误提示')

console.log('  /api/chat/stream 响应状态: ' + (streamStatus.join(', ') || '(未捕获)'))

await page.screenshot({ path: OUT + '/fix-verify.png', fullPage: false })

console.log('\n=== 5. 控制台错误 ===')
if (errors.length === 0) ok('无控制台错误')
else errors.slice(0, 10).forEach((e) => bad(e))

await browser.close()
console.log('\n截图: ' + OUT + '/fix-verify.png')
