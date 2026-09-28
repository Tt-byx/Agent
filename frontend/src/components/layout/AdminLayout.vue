<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const isCollapse = ref(false)

const menuItems = [
  { path: '/admin/dashboard', icon: 'DataLine', label: '数据总览' },
  { path: '/admin/analytics', icon: 'TrendCharts', label: '消费分析' },
  { path: '/admin/visitor-profiles', icon: 'User', label: '游客画像' },
  { path: '/admin/knowledge', icon: 'FolderOpened', label: '知识库' },
  { path: '/admin/conversations', icon: 'ChatDotRound', label: '对话记录' },
  { path: '/admin/ai-assistant', icon: 'ChatLineSquare', label: 'AI助手' },
  { path: '/admin/marketing-report', icon: 'DataAnalysis', label: '营销报告' },
  { path: '/admin/digital-human', icon: 'Avatar', label: '数字人配置' },
  { path: '/admin/system', icon: 'Setting', label: '系统管理' },
]
</script>

<template>
  <el-container class="admin-layout">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="admin-aside">
      <!-- Brand -->
      <div class="brand" :class="{ collapsed: isCollapse }">
        <div class="brand-mark">
          <svg viewBox="0 0 32 32" fill="none" class="brand-icon">
            <path d="M16 4 L6 26 L26 26 Z" fill="#5a8a6a" opacity="0.15"/>
            <path d="M16 8 L9 24 L23 24 Z" stroke="#5a8a6a" stroke-width="1.5" fill="none"/>
            <circle cx="16" cy="14" r="2.5" fill="#5a8a6a"/>
            <path d="M12 20 Q16 17 20 20" stroke="#5a8a6a" stroke-width="1" fill="none"/>
          </svg>
        </div>
        <transition name="fade">
          <div v-if="!isCollapse" class="brand-text">
            <span class="brand-name">景区智脑</span>
            <span class="brand-sub">管理控制台</span>
          </div>
        </transition>
      </div>

      <!-- Navigation -->
      <nav class="nav-section">
        <el-menu
          :default-active="route.path"
          :collapse="isCollapse"
          router
          class="side-menu"
        >
          <el-menu-item
            v-for="item in menuItems"
            :key="item.path"
            :index="item.path"
          >
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
            <template #title>{{ item.label }}</template>
          </el-menu-item>
        </el-menu>
      </nav>

      <!-- Footer -->
      <div class="aside-footer">
        <el-button
          text
          class="collapse-btn"
          @click="isCollapse = !isCollapse"
        >
          <el-icon :size="16">
            <Fold v-if="!isCollapse" />
            <Expand v-else />
          </el-icon>
          <span v-if="!isCollapse" class="collapse-label">收起菜单</span>
        </el-button>
      </div>
    </el-aside>

    <el-container class="main-container">
      <el-header class="top-bar" height="56px">
        <div class="top-bar-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/admin' }">管理后台</el-breadcrumb-item>
            <el-breadcrumb-item>
              {{ menuItems.find(m => m.path === route.path)?.label || '' }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="top-bar-right">
          <el-button
            class="visitor-link"
            @click="router.push('/')"
            plain
            size="small"
          >
            <el-icon :size="14"><Monitor /></el-icon>
            游客端预览
          </el-button>
        </div>
      </el-header>
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
  background: var(--bg-page);

  /* ── Design tokens ── */
  --bg-page: #f7f6f3;
  --bg-card: #ffffff;
  --bg-sidebar: #fafaf8;
  --bg-hover: #f0efec;
  --accent-sage: #5a8a6a;
  --accent-sage-light: #e8f0eb;
  --text-primary: #2d3440;
  --text-secondary: #5a6577;
  --text-tertiary: #8d95a3;
  --border-light: #eae8e4;
  --border-medium: #d5d2cc;
}

/* ── Sidebar ── */
.admin-aside {
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-light);
  display: flex;
  flex-direction: column;
  transition: width 0.28s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
}

/* Brand */
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px 16px;
  border-bottom: 1px solid var(--border-light);
  min-height: 64px;
  box-sizing: border-box;
}

.brand.collapsed {
  justify-content: center;
  padding: 20px 0 16px;
}

.brand-mark {
  flex-shrink: 0;
}

.brand-icon {
  width: 32px;
  height: 32px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 1px;
  line-height: 1.2;
}

.brand-sub {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

/* Navigation */
.nav-section {
  flex: 1;
  padding: 12px 0;
  overflow-y: auto;
}

.side-menu {
  border-right: none;
  background: transparent;
}

.side-menu .el-menu-item {
  height: 44px;
  line-height: 44px;
  margin: 2px 8px;
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s ease;
}

.side-menu .el-menu-item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.side-menu .el-menu-item.is-active {
  background: var(--accent-sage-light);
  color: var(--accent-sage);
  font-weight: 600;
  position: relative;
}

.side-menu .el-menu-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--accent-sage);
}

/* Footer */
.aside-footer {
  padding: 8px;
  border-top: 1px solid var(--border-light);
}

.collapse-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  color: var(--text-tertiary);
  font-size: 12px;
  padding: 8px;
}

.collapse-btn:hover {
  color: var(--text-secondary);
}

.collapse-label {
  white-space: nowrap;
}

/* ── Top bar ── */
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border-light);
  padding: 0 24px;
}

.top-bar-left :deep(.el-breadcrumb) {
  font-size: 13px;
}

.visitor-link {
  border-color: var(--border-medium);
  color: var(--text-secondary);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.visitor-link:hover {
  border-color: var(--accent-sage);
  color: var(--accent-sage);
}

/* ── Main content ── */
.main-container {
  min-width: 0;
}

.main-content {
  background: var(--bg-page);
  padding: 24px;
  overflow-y: auto;
}

/* ── Transitions ── */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
