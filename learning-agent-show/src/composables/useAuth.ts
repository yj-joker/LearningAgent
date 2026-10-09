import { computed, ref } from 'vue'
import { loginUser } from '@/api/auth'
import type { UserCredentials, UserVO } from '@/types/api'
import { AUTH_CHANGED_EVENT, AUTH_STORAGE_KEY, clearStoredUser, readRoleFromToken, readStoredUser, storeUser } from '@/utils/authStorage'

const currentUser = ref<UserVO | null>(readStoredUser())

function refreshAuthentication() {
  const stored = readStoredUser()
  const current = currentUser.value
  // 只有身份真正变化才更新，避免普通页面跳转重复重建通知连接。
  if (stored?.token !== current?.token || stored?.username !== current?.username
      || stored?.role !== current?.role || stored?.avatarUrl !== current?.avatarUrl) currentUser.value = stored
}

// 其他标签页退出或切换账号时同步身份，通知连接也随之关闭或重新认证。
window.addEventListener('storage', event => {
  if (event.key === AUTH_STORAGE_KEY || event.key === null) refreshAuthentication()
})
window.addEventListener(AUTH_CHANGED_EVENT, refreshAuthentication)
// 回到当前页面时重读存储，覆盖手动清理 token 后内存身份仍未更新的情况。
window.addEventListener('focus', refreshAuthentication)
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refreshAuthentication()
})

export function useAuth() {
  const isAuthenticated = computed(() => Boolean(currentUser.value?.token))
  const isAdmin = computed(() => currentUser.value?.role === 'ADMIN')

  function saveAuthenticatedUser(user: UserVO) {
    const normalized = { ...user, role: readRoleFromToken(user.token) ?? user.role ?? null }
    currentUser.value = normalized
    storeUser(normalized)
    return normalized
  }

  async function login(credentials: UserCredentials) {
    const user = await loginUser(credentials)
    if (!user.token) throw new Error('登录暂时无法完成，请稍后重试')
    return saveAuthenticatedUser(user)
  }

  async function loginAdmin(credentials: UserCredentials) {
    const user = await loginUser(credentials)
    if (!user.token) throw new Error('登录暂时无法完成，请稍后重试')
    const role = readRoleFromToken(user.token)
    if (role !== 'ADMIN') throw new Error('该账号不是管理员账号')
    return saveAuthenticatedUser(user)
  }

  function logout() {
    clearStoredUser()
    currentUser.value = null
  }

  return { currentUser, isAuthenticated, isAdmin, login, loginAdmin, logout, refreshAuthentication }
}
