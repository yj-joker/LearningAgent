import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '@/composables/useAuth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '概览', requiresAuth: true },
    },
    {
      path: '/courses',
      name: 'courses',
      component: () => import('@/views/CoursesView.vue'),
      meta: { title: '课程管理', requiresAuth: true },
    },
    {
      path: '/sessions',
      name: 'sessions',
      component: () => import('@/views/SessionsView.vue'),
      meta: { title: '学习会话', requiresAuth: true },
    },
    {
      path: '/api-docs',
      name: 'api-docs',
      component: () => import('@/views/ApiDocsView.vue'),
      meta: { title: '接口说明', requiresAuth: true },
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
    {
      path: '/admin/courses',
      name: 'admin-courses',
      component: () => import('@/views/admin/AdminCoursesView.vue'),
      meta: { title: '课程审核', requiresAdmin: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '工作台')} · Learning Agent`
})

router.beforeEach((to) => {
  const { isAuthenticated, isAdmin } = useAuth()
  if (to.meta.requiresAdmin) {
    if (!isAuthenticated.value) {
      return { name: 'admin-login', query: { redirect: to.fullPath } }
    }
    if (!isAdmin.value) {
      return { name: 'admin-login', query: { denied: '1', redirect: to.fullPath } }
    }
  }
  if (to.meta.requiresAuth && !isAuthenticated.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
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
