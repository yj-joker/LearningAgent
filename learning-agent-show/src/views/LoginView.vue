<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ArrowRight, Eye, EyeOff, LockKeyhole, UserRound } from 'lucide-vue-next'
import AuthShell from '@/components/AuthShell.vue'
import { ApiError } from '@/api/client'
import { useAuth } from '@/composables/useAuth'
import { usePasswordVisibility } from '@/composables/usePasswordVisibility'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const { login } = useAuth()
const { showToast } = useToast()

const form = reactive({
  username: typeof route.query.username === 'string' ? route.query.username : '',
  password: '',
})
const errors = reactive({ username: '', password: '' })
const submitting = ref(false)
const { passwordVisible, togglePasswordVisibility } = usePasswordVisibility()
const registeredNotice = ref(route.query.registered === '1')

function validate() {
  errors.username = form.username.trim() ? '' : '请输入用户名'
  errors.password = form.password ? '' : '请输入密码'
  return !errors.username && !errors.password
}

function userRedirectPath() {
  const value = route.query.redirect
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('/admin') ? value : '/'
}

async function submit() {
  if (!validate()) return
  submitting.value = true
  try {
    const user = await login({ username: form.username.trim(), password: form.password })
    if (user.role === 'ADMIN') {
      showToast('success', '管理员登录成功', `欢迎回来，${user.username}`)
      await router.replace({ name: 'admin-users' })
    } else {
      showToast('success', '欢迎回来', `${user.username}，准备好继续学习了吗？`)
      await router.replace(userRedirectPath())
    }
  } catch (error) {
    const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : '发生未知错误'
    showToast('error', '登录失败', message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell eyebrow="WELCOME BACK" title="登录学习空间" description="登录后继续管理课程，开启下一次专注学习。">
    <div v-if="registeredNotice" class="auth-notice auth-notice-success">
      <span>✓</span><p>账号注册成功，请使用刚刚设置的密码登录。</p>
    </div>
    <form class="auth-form" @submit.prevent="submit">
      <div class="auth-field">
        <label for="login-username">用户名</label>
        <div class="auth-input-wrap">
          <UserRound :size="18" />
          <input id="login-username" v-model="form.username" autocomplete="username" placeholder="输入用户名" @input="errors.username = ''">
        </div>
        <span v-if="errors.username" class="field-error">{{ errors.username }}</span>
      </div>
      <div class="auth-field">
        <div class="auth-label-row"><label for="login-password">密码</label><span>安全登录</span></div>
        <div class="auth-input-wrap">
          <LockKeyhole :size="18" />
          <input id="login-password" v-model="form.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="输入密码" @input="errors.password = ''">
          <button type="button" class="auth-password-toggle" :aria-label="passwordVisible ? '隐藏输入内容' : '显示输入内容'" :aria-pressed="passwordVisible" @mousedown.prevent @click.stop="togglePasswordVisibility">
            <EyeOff v-if="passwordVisible" :size="17" /><Eye v-else :size="17" />
          </button>
        </div>
        <span v-if="errors.password" class="field-error">{{ errors.password }}</span>
      </div>
      <button class="button button-primary auth-submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }} <ArrowRight v-if="!submitting" :size="17" />
      </button>
    </form>
    <p class="auth-switch">还没有账号？ <RouterLink to="/register">创建一个新账号 <ArrowRight :size="14" /></RouterLink></p>
    <p class="auth-footnote">请勿在公共设备上保存登录信息。</p>
  </AuthShell>
</template>
