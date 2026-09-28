<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

const isLogin = ref(true)
const form = ref({ username: '', password: '', nickname: '' })
const loading = ref(false)

const btnText = computed(() => isLogin.value ? '进入导览' : '创建账号')

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
    router.push('/chat')
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
  <div class="login-page">
    <!-- 左侧：视觉区 -->
    <div class="visual-panel">
      <div class="bg-layer">
        <div class="mountain mountain-far"></div>
        <div class="mountain mountain-mid"></div>
        <div class="mountain mountain-near"></div>
        <div class="cloud cloud-1"></div>
        <div class="cloud cloud-2"></div>
        <div class="cloud cloud-3"></div>
        <div class="mist mist-1"></div>
        <div class="mist mist-2"></div>
      </div>

      <div class="visual-content">
        <div class="brand">
          <div class="brand-icon">
            <svg width="40" height="40" viewBox="0 0 32 32" fill="none">
              <path d="M16 2L4 14h8v16h8V14h8L16 2z" fill="#fff" opacity="0.9"/>
              <path d="M10 28c0 0 2-4 6-4s6 4 6 4" stroke="#fff" stroke-width="2" fill="none" opacity="0.7"/>
            </svg>
          </div>
          <h1 class="brand-title">景区 AI 导览</h1>
          <p class="brand-sub">数字人智能导游系统</p>
        </div>

        <div class="visual-features">
          <div class="feature">
            <span class="feature-icon">🗣️</span>
            <span>语音对话 · 自然交互</span>
          </div>
          <div class="feature">
            <span class="feature-icon">🗺️</span>
            <span>路线推荐 · 个性定制</span>
          </div>
          <div class="feature">
            <span class="feature-icon">🤖</span>
            <span>数字人 · 实时口型同步</span>
          </div>
        </div>

        <div class="visual-deco">
          <span>山水之间 · 智能导览 · 数字人伴你同行</span>
        </div>
      </div>
    </div>

    <!-- 右侧：表单区 -->
    <div class="form-panel">
      <div class="form-wrapper">
        <div class="form-header">
          <h2 class="form-title">{{ isLogin ? '欢迎回来' : '创建账号' }}</h2>
          <p class="form-subtitle">{{ isLogin ? '登录后开始智能导览体验' : '注册即可与数字人对话' }}</p>
        </div>

        <!-- Tab 切换 -->
        <div class="mode-tabs">
          <button class="tab" :class="{ active: isLogin }" @click="isLogin = true">登录</button>
          <button class="tab" :class="{ active: !isLogin }" @click="isLogin = false">注册</button>
          <div class="tab-indicator" :class="{ right: !isLogin }"></div>
        </div>

        <!-- 表单 -->
        <div class="inputs">
          <div class="input-group">
            <label class="input-label">用户名</label>
            <div class="input-wrap">
              <svg class="input-icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 10a4 4 0 100-8 4 4 0 000 8zm-7 8a7 7 0 0114 0H3z"/></svg>
              <input v-model="form.username" type="text" placeholder="请输入用户名" @keyup.enter="handleSubmit" />
            </div>
          </div>

          <div class="input-group">
            <label class="input-label">密码</label>
            <div class="input-wrap">
              <svg class="input-icon" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clip-rule="evenodd"/></svg>
              <input v-model="form.password" type="password" placeholder="请输入密码" @keyup.enter="handleSubmit" />
            </div>
          </div>

          <div class="input-group" v-if="!isLogin">
            <label class="input-label">昵称 <span class="optional">选填</span></label>
            <div class="input-wrap">
              <svg class="input-icon" viewBox="0 0 20 20" fill="currentColor"><path d="M10 2a4 4 0 100 8 4 4 0 000-8zM4 18a6 6 0 0112 0H4z"/></svg>
              <input v-model="form.nickname" type="text" placeholder="给自己取个名字吧" @keyup.enter="handleSubmit" />
            </div>
          </div>
        </div>

        <button class="submit-btn" :class="{ loading }" :disabled="loading" @click="handleSubmit">
          <span v-if="!loading">{{ btnText }}</span>
          <span v-else class="btn-loading"><span class="spinner"></span>处理中...</span>
        </button>

        <p class="switch-line">
          {{ isLogin ? '还没有账号？' : '已有账号？' }}
          <a href="#" @click.prevent="toggleMode">{{ isLogin ? '立即注册' : '返回登录' }}</a>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@400;600;700&display=swap');

.login-page {
  --green: #5a8a6a;
  --green-dark: #3d6b4e;
  --ink: #2c3e2f;

  height: 100vh;
  display: flex;
  font-family: 'Noto Serif SC', 'STSong', serif;
  overflow: hidden;
}

/* ══════ 左侧视觉区 ══════ */
.visual-panel {
  flex: 1;
  position: relative;
  background: linear-gradient(160deg, #e8f0eb 0%, #c9d8cc 40%, #a8c0ad 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.bg-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.mountain {
  position: absolute;
  bottom: 0;
  left: -5%;
  width: 110%;
  border-radius: 50% 50% 0 0;
}

.mountain-far {
  height: 55%;
  background: linear-gradient(180deg, rgba(168,195,172,0.35) 0%, rgba(168,195,172,0.1) 100%);
  bottom: 8%;
}

.mountain-mid {
  height: 42%;
  background: linear-gradient(180deg, rgba(140,170,148,0.4) 0%, rgba(140,170,148,0.1) 100%);
  bottom: 3%;
  left: 20%;
  width: 75%;
}

.mountain-near {
  height: 30%;
  background: linear-gradient(180deg, rgba(110,150,120,0.35) 0%, rgba(110,150,120,0.05) 100%);
  left: -10%;
  width: 65%;
}

.cloud {
  position: absolute;
  background: rgba(255,255,255,0.3);
  border-radius: 100px;
  filter: blur(20px);
}

.cloud-1 { width: 350px; height: 45px; top: 15%; left: 5%; animation: drift 25s ease-in-out infinite; }
.cloud-2 { width: 220px; height: 35px; top: 28%; right: 10%; animation: drift 30s ease-in-out infinite reverse; }
.cloud-3 { width: 280px; height: 40px; top: 42%; left: 30%; animation: drift 20s ease-in-out infinite 5s; }

.mist {
  position: absolute;
  width: 100%;
  height: 120px;
  background: linear-gradient(180deg, transparent, rgba(255,255,255,0.15));
  filter: blur(30px);
}

.mist-1 { bottom: 25%; }
.mist-2 { bottom: 10%; opacity: 0.5; }

@keyframes drift {
  0%, 100% { transform: translateX(0); opacity: 0.5; }
  50% { transform: translateX(50px); opacity: 0.85; }
}

.visual-content {
  position: relative;
  z-index: 2;
  text-align: center;
  color: #fff;
  padding: 40px;
}

.brand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 20px;
  background: rgba(255,255,255,0.15);
  backdrop-filter: blur(10px);
  margin-bottom: 20px;
  border: 1px solid rgba(255,255,255,0.2);
}

.brand-title {
  font-size: 36px;
  font-weight: 700;
  margin: 0;
  letter-spacing: 6px;
  text-shadow: 0 2px 20px rgba(0,0,0,0.15);
}

.brand-sub {
  font-size: 14px;
  margin: 10px 0 0;
  opacity: 0.75;
  letter-spacing: 3px;
}

.visual-features {
  margin-top: 48px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: center;
}

.feature {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: rgba(255,255,255,0.8);
  letter-spacing: 1px;
}

.feature-icon {
  font-size: 18px;
}

.visual-deco {
  position: absolute;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
  font-size: 11px;
  color: rgba(255,255,255,0.3);
  letter-spacing: 2px;
}

/* ══════ 右侧表单区 ══════ */
.form-panel {
  width: 480px;
  min-width: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafcfb;
  position: relative;
}

.form-wrapper {
  width: 360px;
  animation: card-enter 0.7s cubic-bezier(0.16, 1, 0.3, 1) both;
}

@keyframes card-enter {
  from { opacity: 0; transform: translateY(24px); }
  to { opacity: 1; transform: translateY(0); }
}

.form-header {
  margin-bottom: 32px;
}

.form-title {
  font-size: 26px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
  letter-spacing: 2px;
}

.form-subtitle {
  font-size: 13px;
  color: rgba(44,62,47,0.4);
  margin: 8px 0 0;
}

/* Tab */
.mode-tabs {
  display: flex;
  position: relative;
  background: rgba(90,138,106,0.06);
  border-radius: 10px;
  padding: 3px;
  margin-bottom: 28px;
}

.tab {
  flex: 1;
  position: relative;
  z-index: 2;
  border: none;
  background: transparent;
  padding: 10px 0;
  font-size: 13px;
  font-weight: 600;
  color: rgba(44,62,47,0.4);
  cursor: pointer;
  transition: color 0.3s;
  font-family: inherit;
  letter-spacing: 2px;
}

.tab.active { color: var(--green-dark); }

.tab-indicator {
  position: absolute;
  top: 3px;
  left: 3px;
  width: calc(50% - 3px);
  height: calc(100% - 6px);
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
  transition: transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

.tab-indicator.right { transform: translateX(100%); }

/* 输入框 */
.inputs {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 7px;
  animation: input-enter 0.5s cubic-bezier(0.16, 1, 0.3, 1) both;
}

.input-group:nth-child(1) { animation-delay: 0.1s; }
.input-group:nth-child(2) { animation-delay: 0.2s; }
.input-group:nth-child(3) { animation-delay: 0.3s; }

@keyframes input-enter {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.input-label {
  font-size: 12px;
  font-weight: 600;
  color: rgba(44,62,47,0.55);
  letter-spacing: 1px;
}

.input-label .optional {
  font-weight: 400;
  color: rgba(44,62,47,0.25);
  font-size: 11px;
}

.input-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1.5px solid rgba(90,138,106,0.12);
  border-radius: 10px;
  padding: 0 14px;
  transition: all 0.25s;
}

.input-wrap:focus-within {
  border-color: var(--green);
  box-shadow: 0 0 0 3px rgba(90,138,106,0.07);
}

.input-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  color: rgba(90,138,106,0.35);
  transition: color 0.25s;
}

.input-wrap:focus-within .input-icon { color: var(--green); }

.input-wrap input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  padding: 12px 0;
  color: var(--ink);
  font-family: inherit;
}

.input-wrap input::placeholder { color: rgba(44,62,47,0.22); }

/* 按钮 */
.submit-btn {
  width: 100%;
  margin-top: 26px;
  padding: 14px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #5a8a6a, #4a7a5a);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 3px;
  cursor: pointer;
  transition: all 0.3s;
  font-family: inherit;
  position: relative;
  overflow: hidden;
}

.submit-btn::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, transparent, rgba(255,255,255,0.12));
  opacity: 0;
  transition: opacity 0.3s;
}

.submit-btn:hover::before { opacity: 1; }
.submit-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 24px rgba(90,138,106,0.3); }
.submit-btn:active { transform: translateY(0); }
.submit-btn.loading { opacity: 0.7; pointer-events: none; }

.btn-loading { display: inline-flex; align-items: center; gap: 8px; }

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.switch-line {
  text-align: center;
  font-size: 12px;
  color: rgba(44,62,47,0.35);
  margin: 20px 0 0;
}

.switch-line a {
  color: var(--green);
  text-decoration: none;
  font-weight: 600;
  transition: color 0.2s;
}

.switch-line a:hover { color: var(--green-dark); }

/* ══════ 响应式：小屏变居中卡片 ══════ */
@media (max-width: 860px) {
  .login-page {
    align-items: center;
    justify-content: center;
    background: linear-gradient(160deg, #e8f0eb, #c9d8cc, #a8c0ad);
  }

  .visual-panel {
    display: none;
  }

  .form-panel {
    width: 400px;
    min-width: unset;
    max-width: 92vw;
    background: rgba(255,255,255,0.75);
    backdrop-filter: blur(20px);
    border-radius: 20px;
    border: 1px solid rgba(255,255,255,0.4);
    box-shadow: 0 8px 40px rgba(60,100,70,0.1);
    padding: 40px 32px;
  }
}
</style>
