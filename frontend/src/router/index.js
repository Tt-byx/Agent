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
        meta: { requiresAuth: true },
      },
      {
        path: 'call',
        name: 'VoiceCall',
        component: () => import('@/views/visitor/VoiceCallView.vue'),
        meta: { requiresAuth: true },
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
        path: 'digital-human',
        name: 'AdminDigitalHuman',
        component: () => import('@/views/admin/DigitalHumanView.vue'),
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
