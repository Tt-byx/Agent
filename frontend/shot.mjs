import { chromium } from 'playwright-core'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = 'http://localhost:8080'
const OUT = 'C:\\Users\\28182\\Desktop\\Agent\\frontend\\shots'

import { mkdirSync } from 'fs'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } })
const page = await ctx.newPage()

// 登录
await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1800)
await page.screenshot({ path: OUT + '/01-login.png' })
const inputs = await page.locator('input').all()
await inputs[0].fill('admin')
await inputs[1].fill('admin123')
await page.locator('button:has-text("进入导览")').first().click()
await page.waitForTimeout(3000)

// 对话页：发一条并等流式完成
await page.goto(BASE + '/chat', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2000)
const ta = page.locator('textarea').first()
if (await ta.count()) {
  await ta.fill('九龙灌浴是什么')
  await page.locator('button:has-text("发送")').first().click()
  await page.waitForTimeout(9000)
}
await page.screenshot({ path: OUT + '/02-chat.png', fullPage: false })

// 各管理页
for (const [path, file] of [
  ['/admin/dashboard', '03-dashboard.png'],
  ['/admin/ops', '04-ops.png'],
  ['/admin/rag-console', '05-rag.png'],
  ['/admin/system', '06-system.png'],
  ['/admin/knowledge', '07-knowledge.png'],
]) {
  await page.goto(BASE + path, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(3000)
  await page.screenshot({ path: OUT + '/' + file, fullPage: false })
  console.log('截图: ' + file)
}

await browser.close()
console.log('完成，输出目录: ' + OUT)
