<script setup lang="ts">
import { BookOpenCheck, Check, GraduationCap, ShieldCheck, Sparkles } from 'lucide-vue-next'

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
        <ul class="auth-feature-list" aria-label="学习工作台能力">
          <li><Check :size="15" />课程、章节与知识点三层结构统一管理</li>
          <li><Check :size="15" />掌握度追踪与复习节奏自动提醒</li>
          <li><Check :size="15" />学习会话随定，下一次专注投入</li>
        </ul>
        <p class="auth-brand-quote">学习不是把信息塞进脑子，而是把结构搭进脑子。</p>
      </div>
      <div class="auth-brand-decoration" aria-hidden="true">
        <span class="auth-decoration-grid" />
        <span class="auth-orbit auth-orbit-one" />
        <span class="auth-orbit auth-orbit-two" />
        <span class="auth-art-card"><BookOpenCheck :size="31" /><span>LEARNING MODE</span></span>
        <span class="auth-art-note"><Sparkles :size="14" /> 结构化学习</span>
        <i class="auth-decoration-dot dot-one" />
        <i class="auth-decoration-dot dot-two" />
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
