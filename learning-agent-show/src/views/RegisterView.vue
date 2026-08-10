<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { ArrowRight, Eye, EyeOff, LockKeyhole, UserRound } from 'lucide-vue-next'
import AuthShell from '@/components/AuthShell.vue'
import { registerUser } from '@/api/auth'
import { ApiError } from '@/api/client'
import { useToast } from '@/composables/useToast'
import { usePasswordVisibility } from '@/composables/usePasswordVisibility'

const router = useRouter()
const { showToast } = useToast()
const form = reactive({ username: '', password: '', confirmPassword: '' })
const errors = reactive({ username: '', password: '', confirmPassword: '' })
const submitting = ref(false)
const { passwordVisible, togglePasswordVisibility } = usePasswordVisibility()

function validate() {
  errors.username = form.username.trim() ? '' : '请输入用户名'
  errors.password = form.password ? '' : '请输入密码'
  errors.confirmPassword = form.confirmPassword ? form.confirmPassword === form.password ? '' : '两次输入的密码不一致' : '请再次输入密码'
  return !errors.username && !errors.password && !errors.confirmPassword
}

async function submit() {
  if (!validate()) return
  submitting.value = true
  try {
    await registerUser({ username: form.username.trim(), password: form.password })
    showToast('success', '注册成功', '账号已创建，请登录后开始学习')
    await router.replace({ name: 'login', query: { registered: '1', username: form.username.trim() } })
  } catch (error) {
    const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : '发生未知错误'
    showToast('error', '注册失败', message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthShell eyebrow="START YOUR JOURNEY" title="创建学习账号" description="注册一个账号，把学习目标和进步记录留在自己的空间里。">
    <form class="auth-form" @submit.prevent="submit">
      <div class="auth-field">
        <label for="register-username">用户名</label>
        <div class="auth-input-wrap">
          <UserRound :size="18" />
          <input id="register-username" v-model="form.username" autocomplete="username" placeholder="设置一个用户名" @input="errors.username = ''">
        </div>
        <span v-if="errors.username" class="field-error">{{ errors.username }}</span>
      </div>
      <div class="auth-field">
        <label for="register-password">密码</label>
        <div class="auth-input-wrap">
          <LockKeyhole :size="18" />
          <input id="register-password" v-model="form.password" :type="passwordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="设置登录密码" @input="errors.password = ''">
          <button type="button" class="auth-password-toggle" :aria-label="passwordVisible ? '隐藏输入内容' : '显示输入内容'" :aria-pressed="passwordVisible" @mousedown.prevent @click.stop="togglePasswordVisibility">
            <EyeOff v-if="passwordVisible" :size="17" /><Eye v-else :size="17" />
          </button>
        </div>
        <span v-if="errors.password" class="field-error">{{ errors.password }}</span>
      </div>
      <div class="auth-field">
        <label for="register-confirm-password">确认密码</label>
        <div class="auth-input-wrap">
          <LockKeyhole :size="18" />
          <input id="register-confirm-password" v-model="form.confirmPassword" :type="passwordVisible ? 'text' : 'password'" autocomplete="new-password" placeholder="再次输入密码" @input="errors.confirmPassword = ''">
        </div>
        <span v-if="errors.confirmPassword" class="field-error">{{ errors.confirmPassword }}</span>
      </div>
      <button class="button button-primary auth-submit" :disabled="submitting">
        {{ submitting ? '注册中…' : '创建账号' }} <ArrowRight v-if="!submitting" :size="17" />
      </button>
    </form>
    <p class="auth-switch">已经有账号？ <RouterLink to="/login">返回登录 <ArrowRight :size="14" /></RouterLink></p>
    <p class="auth-footnote">建议使用长度充足且不与其他网站重复的密码。</p>
  </AuthShell>
</template>
