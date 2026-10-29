import { chromium } from 'playwright-core'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = 'http://localhost:8080'

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const page = await (await browser.newContext()).newPage()

// 先登录
await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(1500)
const inputs = await page.locator('input').all()
await inputs[0].fill('admin')
await inputs[1].fill('admin123')
await page.locator('button:has-text("进入导览")').first().click()
await page.waitForTimeout(2500)

// 逐个页面 dump 文本内容
for (const [path, name] of [
  ['/admin/ops', '成本与性能'],
  ['/admin/rag-console', 'RAG 运维台'],
  ['/admin/knowledge', '知识库'],
  ['/admin/ai-assistant', 'AI助手'],
  ['/admin/system', '系统管理'],
]) {
  await page.goto(BASE + path, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  const txt = (await page.textContent('body')) || ''
  console.log(`\n========== ${name} (${path}) ==========`)
  console.log(txt.replace(/\s+/g, ' ').trim().slice(0, 700))
}

await browser.close()
