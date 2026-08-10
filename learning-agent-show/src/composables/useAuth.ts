import { computed, ref } from 'vue'
import { loginUser } from '@/api/auth'
import type { UserCredentials, UserVO } from '@/types/api'
import { clearStoredUser, readRoleFromToken, readStoredUser, storeUser } from '@/utils/authStorage'

const currentUser = ref<UserVO | null>(readStoredUser())

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
    currentUser.value = null
    clearStoredUser()
  }

  return { currentUser, isAuthenticated, isAdmin, login, loginAdmin, logout }
}
