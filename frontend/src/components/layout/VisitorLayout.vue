<script setup>
/**
 * 游客端外壳。
 *
 * 2026-11-03 改版要点：
 *   1. 顶栏从「白底 + 1px 硬分割线」改成「半透明 + 背景模糊」，让下面的
 *      氛围光晕能透上来 —— 这是 DeepSeek 那种「悬浮在空气里」的观感来源。
 *   2. 主区背景从死灰 #f5f7fa 改成透明，否则首页的渐变光晕会被整块盖住。
 *   3. 补上「登录」入口。原来未登录时右侧是空的，用户从首页根本找不到登录口，
 *      只能靠手动敲 /login。
 */
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

function handleLogout() {
  userStore.logout()
  router.push('/')
}
</script>

<template>
  <el-container class="visitor-layout">
    <el-header class="visitor-header" height="64px">
      <div class="header-left" role="button" tabindex="0"
           @click="router.push('/')" @keyup.enter="router.push('/')">
        <svg class="brand-mark" viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
          <path d="M12 3.5 4 20.5h16L12 3.5Z" stroke="currentColor" stroke-width="1.4"
                stroke-linejoin="round" opacity="0.55"/>
          <path d="M12 9 7.6 18.5h8.8L12 9Z" fill="currentColor" opacity="0.16"/>
          <path d="M12 9 7.6 18.5h8.8L12 9Z" stroke="currentColor" stroke-width="1.2"
                stroke-linejoin="round"/>
        </svg>
        <span class="header-title">景区 AI 导游</span>
      </div>

      <nav class="header-right">
        <button
          v-if="userStore.isAdmin()"
          class="nav-link"
          type="button"
          @click="router.push('/admin')"
        >管理后台</button>

        <template v-if="userStore.user">
          <span class="user-name">{{ userStore.user.nickname || userStore.user.username }}</span>
          <button class="nav-link" type="button" @click="handleLogout">退出</button>
        </template>
        <button
          v-else-if="route.path !== '/login'"
          class="nav-cta"
          type="button"
          @click="router.push('/login')"
        >登录</button>
      </nav>
    </el-header>

    <el-main class="visitor-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.visitor-layout {
  height: 100vh;
  background: var(--color-bg);
}

/* 半透明 + 模糊：让下方氛围层透上来，而不是一刀切的白条 */
.visitor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-6);
  background: rgba(251, 250, 249, 0.72);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--color-line);
  position: relative;
  z-index: 10;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  color: var(--color-brand);
}

.brand-mark { flex-shrink: 0; }

.header-title {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--color-ink);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}

.user-name {
  font-size: var(--text-sm);
  color: var(--color-ink-soft);
}

/* 文字型导航：无边框、无底色，hover 时只做颜色与下划线，
   比默认的 el-button text 更轻 */
.nav-link {
  border: none;
  background: none;
  padding: 6px 0;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-ink-soft);
  cursor: pointer;
  position: relative;
  transition: color var(--dur-fast) ease-out;
}

.nav-link::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 1px;
  background: currentColor;
  transform: scaleX(0);
  transform-origin: right;
  transition: transform var(--dur-base) var(--ease-out);
}

.nav-link:hover {
  color: var(--color-brand);
}

.nav-link:hover::after {
  transform: scaleX(1);
  transform-origin: left;
}

/* 登录是顶栏唯一的主行动，给它一点分量（实心胶囊），但不用夸张的色块 */
.nav-cta {
  min-height: 34px;
  padding: 0 18px;
  border: 1px solid var(--color-brand);
  border-radius: var(--radius-full);
  background: transparent;
  font-family: var(--font-body);
  font-size: var(--text-sm);
  color: var(--color-brand);
  cursor: pointer;
  transition: background var(--dur-fast) ease-out,
              color var(--dur-fast) ease-out;
}

.nav-cta:hover {
  background: var(--color-brand);
  color: #fff;
}

.visitor-main {
  /* 透明：首页的氛围光晕要能铺满整屏 */
  background: transparent;
  padding: 0;
  display: flex;
  justify-content: center;
  min-height: 0;
}

@media (max-width: 640px) {
  .visitor-header { padding: 0 var(--space-4); }
  .header-title { font-size: var(--text-base); }
  .header-right { gap: var(--space-3); }
}
</style>
