/**
 * 连续多轮对话测试（复现并验证「该会话不属于当前账号」问题已修复）。
 * 模拟前端行为：第一轮拿 sessionId，第二轮带上它。
 *
 * SSE 解析用严格状态机：event/data 成对，遇空行派发。
 */
const BASE = 'http://localhost:8080'
const VISITOR = 'v_multiturn_' + Date.now()

async function sse(message, sessionId) {
  const resp = await fetch(BASE + '/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, sessionId, visitorId: VISITOR }),
  })
  const reader = resp.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let sid = null
  let full = ''
  let doneAnswer = null
  let err = null

  const dispatch = (ev, data) => {
    if (ev === 'session') {
      try { sid = JSON.parse(data).sessionId } catch (e) { err = 'session parse fail: ' + e.message }
    } else if (ev === 'token') {
      full += data
    } else if (ev === 'done') {
      doneAnswer = data
    } else if (ev === 'error') {
      err = data
    }
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).replace(/\r$/, '')
      buf = buf.slice(idx + 1)
      if (line === '') continue
      if (line.startsWith('event:')) var ev = line.slice(6).trim()
      else if (line.startsWith('data:')) var data = line.slice(5).replace(/^ /, '')
      // 本项目 SSE 每条事件后跟一个空行，事件结束由下一行的空行触发；
      // 这里简化：遇到新 event: 时派发上一条
      if (line.startsWith('event:') && typeof data !== 'undefined' && evPrev !== undefined) {
        dispatch(evPrev, data)
      }
      if (line.startsWith('event:')) { var evPrev = ev; var data = '' }
    }
  }
  return { sid, answer: doneAnswer || full, err }
}

// 上面状态机过于绕，改用更朴素的做法：按 \n\n 分块
async function sse2(message, sessionId) {
  const resp = await fetch(BASE + '/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ message, sessionId, visitorId: VISITOR }),
  })
  const reader = resp.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let sid = null
  let full = ''
  let doneAnswer = null
  let err = null
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let ev = null, data = ''
      for (const raw of block.split('\n')) {
        const line = raw.replace(/\r$/, '')
        if (line.startsWith('event:')) ev = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '')
      }
      if (ev === 'session') { try { sid = JSON.parse(data).sessionId } catch {} }
      else if (ev === 'token') full += data
      else if (ev === 'done') doneAnswer = data
      else if (ev === 'error') err = data
    }
  }
  return { sid, answer: doneAnswer || full, err }
}

console.log('=== 第 1 轮（无 sessionId，服务端应生成并下发）===')
const r1 = await sse2('灵山胜境几点开门', null)
console.log('  下发 sessionId:', r1.sid)
console.log('  error:', r1.err || '无')
console.log('  回答前50字:', (r1.answer || '').slice(0, 50))

console.log('\n=== 第 2 轮（带上第1轮的 sessionId）===')
const r2 = await sse2('那门票多少钱', r1.sid)
console.log('  sessionId:', r2.sid)
console.log('  error:', r2.err || '无')
console.log('  回答前50字:', (r2.answer || '').slice(0, 50))

console.log('\n=== 第 3 轮 ===')
const r3 = await sse2('谢谢', r1.sid)
console.log('  error:', r3.err || '无')
console.log('  回答前50字:', (r3.answer || '').slice(0, 50))

console.log('\n=== 结果 ===')
if (r1.err || r2.err || r3.err) console.log('✗ 错误:', [r1.err, r2.err, r3.err].filter(Boolean))
else if (r1.sid && r2.sid === r1.sid && r3.sid === r1.sid) console.log('✓ 三轮成功，sessionId 稳定复用:', r1.sid)
else console.log('⚠ sessionId:', r1.sid, r2.sid, r3.sid)
