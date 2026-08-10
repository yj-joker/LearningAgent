<script setup lang="ts">
import { GraduationCap, ShieldCheck } from 'lucide-vue-next'

defineProps<{ title: string; eyebrow: string; description: string }>()
</script>

<template>
  <div class="auth-page">
    <section class="auth-brand-panel">
      <RouterLink class="brand auth-brand" to="/">
        <span class="brand-mark"><GraduationCap :size="24" :stroke-width="2.2" /></span>
        <span><strong>Learning Agent</strong><small>LEARNING WORKSPACE</small></span>
      </RouterLink>
      <div class="auth-brand-copy">
        <span>学习工作台</span>
        <h1>整理课程，<br>保持学习节奏。</h1>
        <p>在同一个空间管理课程、章节和学习记录。</p>
      </div>
      <div class="auth-tip"><ShieldCheck :size="16" /><span>账号凭证仅用于登录和身份验证</span></div>
    </section>

    <section class="auth-form-panel">
      <div class="auth-form-wrap">
        <span class="section-kicker">{{ eyebrow }}</span>
        <h2>{{ title }}</h2>
        <p class="auth-description">{{ description }}</p>
        <slot />
      </div>
    </section>
  </div>
</template>
