<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Eye, EyeOff, GraduationCap, KeyRound, LockKeyhole, ShieldCheck, UserRound } from 'lucide-vue-next'
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
  <div class="admin-login-page">
    <section class="admin-login-brand">
      <div class="admin-login-logo"><span><GraduationCap :size="25" /></span><strong>Learning Agent</strong></div>
      <div class="admin-login-copy">
        <span class="admin-security-pill"><ShieldCheck :size="14" /> SECURE ADMIN ACCESS</span>
        <h1>专注管理，<br><em>让学习平台稳定生长。</em></h1>
        <p>统一查看用户、筛选账号并掌握平台用户构成。</p>
      </div>
    </section>

    <section class="admin-login-form-panel">
      <div class="admin-login-form-wrap">
        <span class="admin-form-icon"><KeyRound :size="24" /></span>
        <span class="section-kicker">ADMINISTRATOR</span>
        <h2>管理员登录</h2>
        <p>请使用管理员账号登录。</p>
        <div v-if="route.query.denied === '1'" class="admin-denied-notice">当前账号没有管理员权限，请更换管理员账号。</div>
        <form class="auth-form" @submit.prevent="submit">
          <div class="auth-field">
            <label for="admin-username">管理员账号</label>
            <div class="auth-input-wrap"><UserRound :size="18" /><input id="admin-username" v-model="form.username" autocomplete="username" placeholder="输入管理员用户名" @input="errors.username = ''"></div>
            <span v-if="errors.username" class="field-error">{{ errors.username }}</span>
          </div>
          <div class="auth-field">
            <label for="admin-password">密码</label>
            <div class="auth-input-wrap">
              <LockKeyhole :size="18" />
              <input id="admin-password" v-model="form.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="输入管理员密码" @input="errors.password = ''">
              <button type="button" class="auth-password-toggle" :aria-label="passwordVisible ? '隐藏输入内容' : '显示输入内容'" :aria-pressed="passwordVisible" @mousedown.prevent @click.stop="togglePasswordVisibility"><EyeOff v-if="passwordVisible" :size="17" /><Eye v-else :size="17" /></button>
            </div>
            <span v-if="errors.password" class="field-error">{{ errors.password }}</span>
          </div>
          <button class="button admin-login-submit" :disabled="submitting">{{ submitting ? '身份验证中…' : '进入管理控制台' }} <ArrowRight v-if="!submitting" :size="17" /></button>
        </form>
        <p class="admin-login-note"><ShieldCheck :size="13" /> 管理员账号仅可进入管理端。</p>
      </div>
    </section>
  </div>
</template>
