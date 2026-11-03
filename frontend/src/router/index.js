import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  // ========== 游客端 ==========
  {
    path: '/',
    component: () => import('@/components/layout/VisitorLayout.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/visitor/HomeView.vue'),
      },
      {
        path: 'login',
        name: 'Login',
        component: () => import('@/views/visitor/LoginView.vue'),
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('@/views/visitor/ChatView.vue'),
        // 2026-11-03 修复：这里原来挂着 meta: { requiresAuth: true }，
        // 导致游客点「开始对话」直接被守卫弹到登录页。
        //
        // 设计上游客本来就能对话：
        //   - 后端 POST /api/chat 支持匿名，还有 POST /api/auth/guest 专门签发游客凭证
        //   - 游客唯一缺的是「历史会话列表」（会话归属靠 user_id，游客没有）
        //
        // 所以登录应该是**可选项**，不是进对话页的前置条件。
        // 需要登录的能力（历史会话、个人信息）在页面内部自己判断，不放在路由上。
      },
    ],
  },
  // ========== 管理后台 ==========
  {
    path: '/admin',
    component: () => import('@/components/layout/AdminLayout.vue'),
    redirect: '/admin/dashboard',
    meta: { requiresAdmin: true },
    children: [
      {
        path: 'dashboard',
        name: 'AdminDashboard',
        component: () => import('@/views/admin/DashboardView.vue'),
      },
      {
        path: 'knowledge',
        name: 'AdminKnowledge',
        component: () => import('@/views/admin/KnowledgeView.vue'),
      },
      {
        path: 'conversations',
        name: 'AdminConversations',
        component: () => import('@/views/admin/ConversationsView.vue'),
      },
      {
        path: 'analytics',
        name: 'AdminAnalytics',
        component: () => import('@/views/admin/AnalyticsView.vue'),
      },
      {
        path: 'visitor-profiles',
        name: 'AdminVisitorProfiles',
        component: () => import('@/views/admin/VisitorProfilesView.vue'),
      },
      {
        path: 'system',
        name: 'AdminSystem',
        component: () => import('@/views/admin/SystemConfigView.vue'),
      },
      {
        path: 'ai-assistant',
        name: 'AdminAIAssistant',
        component: () => import('@/views/admin/AdminAIView.vue'),
      },
      {
        path: 'marketing-report',
        name: 'AdminMarketingReport',
        component: () => import('@/views/admin/MarketingReportView.vue'),
      },
      {
        path: 'ops',
        name: 'AdminOps',
        component: () => import('@/views/admin/OpsView.vue'),
      },
      {
        path: 'rag-console',
        name: 'AdminRagConsole',
        component: () => import('@/views/admin/RagConsoleView.vue'),
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const user = JSON.parse(localStorage.getItem('user') || 'null')

  // 需要登录
  if (to.meta.requiresAuth && !token) {
    return next('/login')
  }

  // 需要管理员权限（检查当前路由及其父级）
  const needsAdmin = to.matched.some(r => r.meta.requiresAdmin)
  if (needsAdmin) {
    if (!token) return next('/login')
    if (user?.role !== 'admin') {
      return next('/') // 非管理员跳回首页
    }
  }

  next()
})

export default router
