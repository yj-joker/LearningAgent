<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowRight, Eye, EyeOff, LockKeyhole, UserRound } from 'lucide-vue-next'
import AuthShell from '@/components/AuthShell.vue'
import { ApiError } from '@/api/client'
import { useAuth } from '@/composables/useAuth'
import { usePasswordVisibility } from '@/composables/usePasswordVisibility'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const { loginAdmin } = useAuth()
const { showToast } = useToast()
const form = reactive({ username: '', password: '' })
const errors = reactive({ username: '', password: '' })
const submitting = ref(false)
const { passwordVisible, togglePasswordVisibility } = usePasswordVisibility()

function validate() {
  errors.username = form.username.trim() ? '' : '请输入管理员用户名'
  errors.password = form.password ? '' : '请输入密码'
  return !errors.username && !errors.password
}

function redirectPath() {
  const value = route.query.redirect
  return typeof value === 'string' && value.startsWith('/admin') ? value : '/admin/users'
}

async function submit() {
  if (!validate()) return
  submitting.value = true
  try {
    const user = await loginAdmin({ username: form.username.trim(), password: form.password })
    showToast('success', '管理员登录成功', `欢迎回来，${user.username}`)
    await router.replace(redirectPath())
  } catch (error) {
    showToast('error', '无法进入管理端', error instanceof ApiError ? error.message : error instanceof Error ? error.message : '发生未知错误')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell eyebrow="ADMIN ACCESS" title="管理员登录" description="登录后进入管理控制台，安全维护学习空间。">
    <div v-if="route.query.denied === '1'" class="auth-notice auth-notice-warning">
      <span>!</span><p>当前账号没有管理员权限，请更换管理员账号。</p>
    </div>
    <form class="auth-form" @submit.prevent="submit">
      <div class="auth-field">
        <label for="admin-username">管理员账号</label>
        <div class="auth-input-wrap">
          <UserRound :size="18" />
          <input id="admin-username" v-model="form.username" autocomplete="username" placeholder="输入管理员用户名" @input="errors.username = ''">
        </div>
        <span v-if="errors.username" class="field-error">{{ errors.username }}</span>
      </div>
      <div class="auth-field">
        <div class="auth-label-row"><label for="admin-password">密码</label><span>安全登录</span></div>
        <div class="auth-input-wrap">
          <LockKeyhole :size="18" />
          <input id="admin-password" v-model="form.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="输入管理员密码" @input="errors.password = ''">
          <button type="button" class="auth-password-toggle" :aria-label="passwordVisible ? '隐藏输入内容' : '显示输入内容'" :aria-pressed="passwordVisible" @mousedown.prevent @click.stop="togglePasswordVisibility">
            <EyeOff v-if="passwordVisible" :size="17" /><Eye v-else :size="17" />
          </button>
        </div>
        <span v-if="errors.password" class="field-error">{{ errors.password }}</span>
      </div>
      <button class="button button-primary auth-submit" :disabled="submitting">
        {{ submitting ? '身份验证中…' : '进入管理控制台' }} <ArrowRight v-if="!submitting" :size="17" />
      </button>
    </form>
    <p class="auth-switch">想使用普通用户身份？ <RouterLink to="/login">切换到用户登录 <ArrowRight :size="14" /></RouterLink></p>
    <p class="auth-footnote">管理员账号仅可进入管理端。</p>
  </AuthShell>
</template>
