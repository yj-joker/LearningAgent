import { computed, ref } from 'vue'
import { loginUser } from '@/api/auth'
import type { UserCredentials, UserVO } from '@/types/api'
import { clearStoredUser, readStoredUser, storeUser } from '@/utils/authStorage'

const currentUser = ref<UserVO | null>(readStoredUser())

export function useAuth() {
  const isAuthenticated = computed(() => Boolean(currentUser.value?.token))

  async function login(credentials: UserCredentials) {
    const user = await loginUser(credentials)
    if (!user.token) throw new Error('登录响应中没有返回 token，请检查后端配置')
    currentUser.value = user
    storeUser(user)
    return user
  }

  function logout() {
    currentUser.value = null
    clearStoredUser()
  }

  return { currentUser, isAuthenticated, login, logout }
}
