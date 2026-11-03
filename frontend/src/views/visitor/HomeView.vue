<script setup>
/**
 * 首页 —— 视觉方向：极致极简 + 空气感。
 *
 * 改版前：居中标题 + 副标题 + 一颗 Element Plus 默认蓝按钮，45 个字，
 * 正好是全套「AI 生成页」的标准模板（反 AI 味清单第 3 条）。
 *
 * 改版后：把「一颗按钮」换成**一颗能直接提问的输入框** —— 这是整个首页的
 * 记忆点。用户不必先理解「这里有个按钮」再点进去，而是当场就能问。
 * 提问后把内容带进对话页（?q=），进去自动发送，少一次点击。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const question = ref('')

/** 三个示例问题：降低「不知道能问什么」的门槛，点了直接问 */
const suggestions = [
  '九龙灌浴几点开始？',
  '带小孩玩一天，路线怎么安排？',
  '灵山大佛到底有多高？',
]

function ask(text) {
  const q = (text ?? question.value).trim()
  router.push(q ? { path: '/chat', query: { q } } : '/chat')
}
</script>

<template>
  <div class="home">
    <!-- 氛围层：两道极淡的径向光晕。透明度压到 8%~10% ——
         再高就变成「白底蓝紫渐变」那种廉价感，这里只求「有空气」而不「有颜色」 -->
    <div class="atmosphere" aria-hidden="true"></div>

    <main class="hero">
      <p class="eyebrow">灵山胜境 · AI 导游</p>

      <h1 class="title">山水有灵</h1>
      <p class="subtitle">问一句，便知这座山的来处与去处</p>

      <form class="ask" @submit.prevent="ask()">
        <input
          v-model="question"
          class="ask-input"
          type="text"
          placeholder="问点什么，比如：九龙灌浴几点开始"
          aria-label="向 AI 导游提问"
        />
        <button
          class="ask-send"
          type="submit"
          :disabled="!question.trim()"
          aria-label="发送"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
            <path d="M12 19V5M12 5l-6 6M12 5l6 6" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
      </form>

      <div class="suggestions">
        <button
          v-for="s in suggestions"
          :key="s"
          class="suggestion"
          type="button"
          @click="ask(s)"
        >{{ s }}</button>
      </div>
    </main>

    <nav class="quick" aria-label="快捷入口">
      <button class="quick-item" type="button" @click="router.push('/chat')">
        <span class="dot" aria-hidden="true"></span>
        和 AI 导游聊聊
      </button>
      <button
        v-if="!userStore.isLoggedIn()"
        class="quick-item"
        type="button"
        @click="router.push('/login')"
      >
        <span class="dot" aria-hidden="true"></span>
        登录，保存你的行程
      </button>
      <button
        v-if="userStore.isAdmin()"
        class="quick-item"
        type="button"
        @click="router.push('/admin')"
      >
        <span class="dot" aria-hidden="true"></span>
        管理后台
      </button>
    </nav>
  </div>
</template>

<style scoped>
.home {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;                     /* 必须撑满：否则 el-main 的 center 会让它按内容收缩，
                                      光晕的 inset:0 就只覆盖中间一块，露出矩形边界 */
  min-height: calc(100vh - 64px);
  padding: var(--space-6) var(--space-5) var(--space-7);
  overflow: hidden;
}

/* ── 氛围层 ──
   用 fixed 铺满整个视口，而不是跟着 .home 的盒子走 —— 这样滚动或内容变化时
   光晕始终覆盖全屏，不会出现「一块有色区域浮在白底上」的破绽。
   透明度压得很低（5%~6%），只做「有空气」的暗示，不做「有颜色」的表达。 */
.atmosphere {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background:
    radial-gradient(60rem 42rem at 18% -6%, rgba(31, 92, 67, 0.07), transparent 65%),
    radial-gradient(52rem 38rem at 88% 8%, rgba(184, 134, 11, 0.045), transparent 62%),
    radial-gradient(48rem 36rem at 50% 108%, rgba(31, 92, 67, 0.04), transparent 68%);
}

.hero {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 720px;
  text-align: center;
}

/* ── 标题：宋体 + 正字距。
      中文大字用负字距会让笔画挤在一起，这里给 0.04em 让字「站得开」 ── */
.eyebrow {
  margin-bottom: var(--space-5);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  letter-spacing: 0.18em;
  color: var(--color-muted);
}

.title {
  margin: 0;
  font-family: var(--font-display);
  font-size: var(--text-5xl);
  font-weight: 600;
  line-height: 1.05;
  letter-spacing: 0.04em;
  color: var(--color-ink);
}

.subtitle {
  margin: var(--space-4) 0 var(--space-7);
  font-family: var(--font-body);
  font-size: var(--text-base);
  line-height: 1.7;
  letter-spacing: 0.02em;
  color: var(--color-ink-soft);
}

/* ── 核心交互：大圆角输入框 ── */
.ask {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 8px 8px 8px var(--space-5);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-2);
  backdrop-filter: blur(12px);
  transition: border-color var(--dur-fast) ease-out,
              box-shadow var(--dur-fast) ease-out;
}

.ask:focus-within {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 4px var(--color-brand-glow), var(--shadow-2);
}

.ask-input {
  flex: 1;
  min-width: 0;
  height: 44px;
  border: none;
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-base);
  color: var(--color-ink);
  outline: none;
}

.ask-input::placeholder {
  color: var(--color-muted);
}

.ask-send {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border: none;
  border-radius: var(--radius-full);
  background: var(--color-brand);
  color: #fff;
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              transform var(--dur-fast) ease-out;
}

.ask-send:hover:not(:disabled) {
  background: var(--color-brand-hover);
  transform: translateY(-1px);
}

.ask-send:disabled {
  background: var(--color-line-strong);
  cursor: not-allowed;
}

.ask-send:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 3px;
}

/* ── 示例问题 ── */
.suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
  margin-top: var(--space-4);
}

.suggestion {
  padding: 7px 14px;
  border: none;
  border-radius: var(--radius-full);
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-muted);
  cursor: pointer;
  transition: color var(--dur-fast) ease-out,
              background var(--dur-fast) ease-out;
}

.suggestion:hover {
  color: var(--color-brand);
  background: var(--color-brand-soft);
}

/* ── 底部快捷入口：胶囊，与 DeepSeek 底部三颗同构 ── */
.quick {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-8);
}

.quick-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 44px;                /* 触摸目标 ≥44px */
  padding: 0 20px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-full);
  background: rgba(255, 255, 255, 0.7);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-ink-soft);
  cursor: pointer;
  transition: border-color var(--dur-fast) ease-out,
              color var(--dur-fast) ease-out,
              transform var(--dur-fast) ease-out;
}

.quick-item:hover {
  border-color: var(--color-brand);
  color: var(--color-brand);
  transform: translateY(-2px);
}

.quick-item:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

/* 一个小小的暖金点：全页唯一的强调色，只出现在这里 */
.dot {
  width: 5px;
  height: 5px;
  border-radius: var(--radius-full);
  background: var(--color-accent);
}

/* ── 入场序列：标题 → 输入框 → 示例 → 快捷入口，错峰 80ms ── */
@media (prefers-reduced-motion: no-preference) {
  .eyebrow, .title, .subtitle, .ask, .suggestions, .quick {
    opacity: 0;
    transform: translateY(16px);
    animation: rise var(--dur-slow) var(--ease-out) forwards;
  }
  .eyebrow    { animation-delay: 0ms; }
  .title      { animation-delay: 80ms; }
  .subtitle   { animation-delay: 160ms; }
  .ask        { animation-delay: 240ms; }
  .suggestions{ animation-delay: 320ms; }
  .quick      { animation-delay: 400ms; }
}

@keyframes rise {
  to { opacity: 1; transform: none; }
}

/* ── 响应式 ── */
@media (max-width: 640px) {
  .title { font-size: var(--text-4xl); }
  .subtitle { font-size: var(--text-sm); }
  .ask { border-radius: var(--radius-lg); padding-left: var(--space-4); }
  .quick { flex-direction: column; align-items: stretch; }
  .quick-item { justify-content: center; }
}
</style>
