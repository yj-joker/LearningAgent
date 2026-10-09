import { createRouter, createWebHistory } from 'vue-router'
import { watch } from 'vue'
import { useAuth } from '@/composables/useAuth'
import { getStoredToken } from '@/utils/authStorage'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '概览', requiresUser: true },
    },
    {
      path: '/courses',
      name: 'courses',
      component: () => import('@/views/CoursesView.vue'),
      meta: { title: '课程管理', requiresUser: true },
    },
    {
      path: '/courses/:courseId/edit',
      name: 'course-editor',
      component: () => import('@/views/CourseEditorView.vue'),
      meta: { title: '课程编辑', requiresUser: true },
    },
    {
      path: '/chapters/:courseId?',
      name: 'chapters',
      redirect: to => to.params.courseId
        ? { name: 'course-editor', params: { courseId: to.params.courseId }, query: to.query }
        : { name: 'courses' },
    },
    {
      path: '/knowledge-points/:courseId?/:chapterId?',
      name: 'knowledge-points',
      redirect: to => to.params.courseId
        ? { name: 'course-editor', params: { courseId: to.params.courseId }, query: { ...to.query, chapter: to.params.chapterId || to.query.chapter } }
        : { name: 'courses' },
    },
    {
      path: '/knowledge-bases',
      name: 'knowledge-bases',
      component: () => import('@/views/KnowledgeBaseView.vue'),
      meta: { title: '知识库', requiresUser: true },
    },
    {
      path: '/sessions',
      name: 'sessions',
      component: () => import('@/views/SessionsView.vue'),
      meta: { title: '全部历史', requiresUser: true },
    },
    {
      path: '/ai-assistant',
      name: 'agent-chat',
      component: () => import('@/views/AgentChatView.vue'),
      props: { mode: 'COURSE', standalone: false },
      meta: { title: '课程学习', requiresUser: true },
    },
    {
      path: '/ai-assistant/chat',
      name: 'agent-chat-standalone',
      component: () => import('@/views/AgentChatView.vue'),
      props: { mode: 'CHAT', standalone: true },
      meta: { title: '独立问答', requiresUser: true },
    },
    {
      path: '/ai-assistant/focus',
      name: 'agent-focus',
      component: () => import('@/views/AgentChatView.vue'),
      props: { mode: 'FOCUS', standalone: true },
      meta: { title: '专注学习', requiresUser: true },
    },
    {
      path: '/learning-plans',
      name: 'learning-plans',
      component: () => import('@/views/LearningPlanDraftsView.vue'),
      meta: { title: '学习计划草案', requiresUser: true },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { title: '注册' },
    },
    {
      path: '/admin/login',
      name: 'admin-login',
      component: () => import('@/views/admin/AdminLoginView.vue'),
      meta: { title: '管理员登录' },
    },
    {
      path: '/admin',
      redirect: '/admin/users',
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/admin/AdminUsersView.vue'),
      meta: { title: '用户管理', requiresAdmin: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

const { currentUser, isAuthenticated, isAdmin, refreshAuthentication } = useAuth()
let refreshingRouteAuth = false

function redirectWithoutToken() {
  const route = router.currentRoute.value
  if (getStoredToken() || (!route.meta.requiresUser && !route.meta.requiresAdmin)) return
  // 保留原位置，重新登录后能继续当前课程和章节。
  void router.replace({ name: route.meta.requiresAdmin ? 'admin-login' : 'login', query: { redirect: route.fullPath } })
}

// 请求失败和其他标签页退出都能立即触发跳转，无需等待下一次点击导航。
watch(() => currentUser.value?.token, () => {
  if (!refreshingRouteAuth) redirectWithoutToken()
}, { flush: 'sync' })

router.afterEach((to, _from, failure) => {
  // 被登录跳转取消的旧导航也会走到这里，不能让它再启动一次跳转。
  if (failure) return
  document.title = `${String(to.meta.title ?? '工作台')} · Learning Agent`
  redirectWithoutToken()
})

router.beforeEach((to) => {
  // 每次进页面都重读实际 token，避免仅凭旧的内存身份放行。
  // 此处由当前导航返回登录页，避免同步状态时又启动第二次跳转。
  refreshingRouteAuth = true
  try { refreshAuthentication() }
  finally { refreshingRouteAuth = false }
  if (to.meta.requiresAdmin) {
    if (!isAuthenticated.value) {
      return { name: 'admin-login', query: { redirect: to.fullPath } }
    }
    if (!isAdmin.value) {
      return { name: 'admin-login', query: { denied: '1', redirect: to.fullPath } }
    }
  }
  if (to.meta.requiresUser) {
    if (!isAuthenticated.value) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (isAdmin.value) {
      return { name: 'admin-users' }
    }
  }
  if (to.name === 'admin-login' && isAdmin.value) {
    return { name: 'admin-users' }
  }
  if ((to.name === 'login' || to.name === 'register') && isAuthenticated.value) {
    return isAdmin.value ? { name: 'admin-users' } : { name: 'dashboard' }
  }
  return true
})

export default router
