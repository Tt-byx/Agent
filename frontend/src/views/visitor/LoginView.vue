<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const isLogin = ref(true)
const form = ref({ username: '', password: '', nickname: '' })
const loading = ref(false)

const btnText = computed(() => isLogin.value ? '进入导览' : '创建账号')

/**
 * 2026-11-03 修复：登录后要回到「被踢出来之前的页面」。
 *
 * 原实现固定 `router.push('/chat')` —— 从 /admin/knowledge 被拦到登录页，
 * 登录成功后却落到对话页，用户得自己再点回去。request.js 明明已经拼好了
 * `?redirect=...`，但这里根本没读。
 *
 * 安全约束：只接受**站内绝对路径**（单个 / 开头）。
 * 否则 `?redirect=//evil.com` 会变成协议相对 URL，登录后被送到外部站点（开放重定向）。
 */
function resolveRedirect() {
  const raw = route.query.redirect
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) {
    return '/chat'
  }
  return raw
}

async function handleSubmit() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  loading.value = true
  try {
    if (isLogin.value) {
      await userStore.loginUser(form.value.username, form.value.password)
      ElMessage.success('登录成功')
    } else {
      if (!form.value.nickname) form.value.nickname = form.value.username
      await userStore.registerUser(form.value.username, form.value.password, form.value.nickname)
      ElMessage.success('注册成功')
    }
    router.push(resolveRedirect())
  } catch (err) {
    ElMessage.error(err.message || '操作失败')
  } finally {
    loading.value = false
  }
}

function toggleMode() {
  isLogin.value = !isLogin.value
  form.value = { username: '', password: '', nickname: '' }
}
</script>

<template>
  <div class="auth">
    <!-- 氛围层：和首页同一套极淡光晕，保持全站一致 -->
    <div class="auth-atmosphere" aria-hidden="true"></div>

    <main class="auth-card">
      <div class="auth-mark" aria-hidden="true">
        <svg viewBox="0 0 32 32" width="34" height="34" fill="none">
          <path d="M16 4 6 26h20L16 4Z" stroke="currentColor" stroke-width="1.3"
                stroke-linejoin="round" opacity="0.45"/>
          <path d="M16 9.5 9.5 24.5h13L16 9.5Z" stroke="currentColor"
                stroke-width="1.4" stroke-linejoin="round"/>
        </svg>
      </div>

      <h1 class="auth-title">景区 AI 导游</h1>
      <p class="auth-subtitle">{{ isLogin ? '登录后，行程与偏好都会记着' : '创建账号，开始你的导览' }}</p>

      <form class="auth-form" @submit.prevent="handleSubmit">
        <label class="field">
          <svg class="field-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M10 10a4 4 0 100-8 4 4 0 000 8zm-7 8a7 7 0 0114 0H3z"/>
          </svg>
          <input v-model="form.username" type="text" placeholder="请输入用户名"
                 autocomplete="username" @keyup.enter="handleSubmit" />
        </label>

        <label class="field">
          <svg class="field-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path fill-rule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clip-rule="evenodd"/>
          </svg>
          <input v-model="form.password" type="password" placeholder="请输入密码"
                 autocomplete="current-password" @keyup.enter="handleSubmit" />
        </label>

        <label v-if="!isLogin" class="field">
          <!-- 昵称用「人 + 笔」的意象，不要跟密码字段的锁图标混淆 -->
          <svg class="field-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M9 10a4 4 0 100-8 4 4 0 000 8zm-7 8a7 7 0 0111.6-5.4l-1.4 1.4A5 5 0 004 18H2z"/>
            <path d="M17.7 11.3a1 1 0 010 1.4l-4.2 4.2-1.9.5.5-1.9 4.2-4.2a1 1 0 011.4 0z"/>
          </svg>
          <input v-model="form.nickname" type="text" placeholder="昵称（可选，默认用用户名）"
                 @keyup.enter="handleSubmit" />
        </label>

        <button class="btn-submit" type="submit" :disabled="loading">
          {{ loading ? '处理中…' : btnText }}
        </button>
      </form>

      <p class="auth-switch">
        {{ isLogin ? '还没有账号？' : '已经有账号了？' }}
        <button class="link" type="button" @click="toggleMode">
          {{ isLogin ? '立即注册' : '返回登录' }}
        </button>
      </p>

      <p class="auth-note">
        也可以
        <button class="link" type="button" @click="router.push('/chat')">以游客身份直接对话</button>
      </p>
    </main>
  </div>
</template>

<style scoped>
/* ═══════════════════════════════════════════════════════════
   登录页 —— 对照 chat.deepseek.com/sign_in 重做
   ───────────────────────────────────────────────────────────
   改版前的结构：左右分栏，左边一整块深绿渐变装饰面板 + 三个功能点，
   右边一个白卡片表单。视觉重心被装饰抢走，真正要做的事（登录）反而次要。
   DeepSeek 的做法相反：**纯白、严格居中、没有装饰**，一个 logo、
   两三个输入框、一颗主按钮，剩下的全是留白。
   ═══════════════════════════════════════════════════════════ */

.auth {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;     /* 必须撑满：父级 el-main 是 justify-content:center 的 flex 容器，
                      不写这行 .auth 会收缩到内容宽度（实测只有 280px），
                      里面 .auth-card 的 max-width:384px 就永远不生效 */
  min-height: calc(100vh - 64px);
  /* 上下留白要足够大，否则内容块在大屏上会「贴着中间」显得局促 */
  padding: 96px var(--space-5);
  background: var(--color-bg);
  overflow: hidden;
}

/* 极淡光晕，和首页/对话页同一套语言；透明度压到 5% 以内，只是「有空气」 */
.auth-atmosphere {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background:
    radial-gradient(46rem 32rem at 50% -8%, rgba(31, 92, 67, 0.06), transparent 62%),
    radial-gradient(38rem 28rem at 88% 92%, rgba(184, 134, 11, 0.035), transparent 60%);
}

.auth-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 384px;    /* 比 DeepSeek 略宽一点：我们有「用户名+密码」两栏，太窄会挤 */
  text-align: center;
}

.auth-mark {
  color: var(--color-brand);
  margin-bottom: 26px;
}

.auth-title {
  margin: 0;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--color-ink);
}

.auth-subtitle {
  margin: 10px 0 64px;
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--color-muted);
}

/* ── 表单 ── */
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 输入框做成「图标 + 无边框内容」的整块，边框画在外层 label 上，
   这样点图标也能聚焦 —— 比把边框画在 input 上更好点 */
.field {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 46px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: #F7F8FA;          /* 浅灰填充：聚焦前几乎看不到边框 */
  cursor: text;
  transition: border-color var(--dur-fast) ease-out,
              box-shadow var(--dur-fast) ease-out;
}

.field:focus-within {
  border-color: var(--color-brand);
  background: var(--color-surface);   /* 聚焦时转为白底，边界更清楚 */
  box-shadow: 0 0 0 3px var(--color-brand-glow);
}

.field-icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  color: var(--color-muted);
  transition: color var(--dur-fast) ease-out;
}

.field:focus-within .field-icon {
  color: var(--color-brand);
}

.field input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: none;
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-ink);
  outline: none;
}

.field input::placeholder {
  color: var(--color-muted);
}

/* Chrome 自动填充会强行给 input 刷一层白底（inset 阴影 hack 之外没别的办法），
   这里必须把它覆盖成和外层 .field 一样的浅灰 —— 否则会出现
   「图标区浅灰、输入区白色」的割裂感。全局那条规则用的是 --color-surface（白），
   那是给白底输入框用的，登录页需要单独覆盖。 */
.field input:-webkit-autofill,
.field input:-webkit-autofill:hover,
.field input:-webkit-autofill:focus {
  -webkit-box-shadow: 0 0 0 1000px #F7F8FA inset;
  -webkit-text-fill-color: var(--color-ink);
  transition: background-color 9999s ease-out;
}

/* 主按钮：墨绿实心 + 10px 圆角。
   注意不是胶囊 —— DeepSeek 的登录按钮是圆角矩形，胶囊留给次级操作。 */
.btn-submit {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 46px;
  margin-top: 22px;
  border: none;
  border-radius: 10px;
  background: var(--color-brand);
  font-family: var(--font-body);
  font-size: var(--text-base);
  letter-spacing: 0.06em;
  color: #fff;
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              transform var(--dur-fast) ease-out;
}

.btn-submit:hover:not(:disabled) {
  background: var(--color-brand-hover);
  transform: translateY(-1px);
}

.btn-submit:disabled {
  background: var(--color-line-strong);
  color: var(--color-muted);
  cursor: not-allowed;
}

.btn-submit:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

/* ── 切换与补充入口 ── */
.auth-switch {
  margin-top: 30px;
  font-size: var(--text-sm);
  color: var(--color-muted);
}

.auth-note {
  margin-top: 14px;
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.link {
  border: none;
  background: none;
  padding: 2px 1px;
  font-family: var(--font-body);
  font-size: inherit;
  color: var(--color-brand);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-thickness: 1px;
}

.link:hover {
  color: var(--color-brand-hover);
}

.link:focus-visible {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
  border-radius: 3px;
}

@media (max-width: 480px) {
  .auth-card { max-width: 100%; }
  .auth-title { font-size: 20px; }
}
</style>
