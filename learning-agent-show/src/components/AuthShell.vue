<script setup lang="ts">
import { BookOpenText, Check, GraduationCap, Lightbulb, Sparkles } from 'lucide-vue-next'

defineProps<{
  title: string
  eyebrow: string
  description: string
}>()
</script>

<template>
  <div class="auth-page">
    <section class="auth-brand-panel">
      <div class="auth-brand-top">
        <RouterLink class="brand auth-brand" to="/">
          <span class="brand-mark"><GraduationCap :size="25" :stroke-width="2.2" /></span>
          <span><strong>Learning</strong><small>AGENT</small></span>
        </RouterLink>
        <span class="auth-version">WORKSPACE 1.0</span>
      </div>
      <div class="auth-brand-copy">
        <span class="hero-kicker"><Sparkles :size="14" /> LEARN WITH INTENTION</span>
        <h1>把好奇心，<br><em>变成看得见的进步。</em></h1>
        <p>为每个学习目标建立一条清晰路径，从今天开始持续构建你的知识地图。</p>
      </div>
      <div class="auth-brand-art" aria-hidden="true">
        <div class="auth-orbit auth-orbit-one" />
        <div class="auth-orbit auth-orbit-two" />
        <div class="auth-art-card"><BookOpenText :size="43" /><span>持续学习</span></div>
        <span class="auth-art-spark spark-a">✦</span>
        <span class="auth-art-spark spark-b">✦</span>
        <div class="auth-art-note"><Check :size="13" /> 今天也有进步</div>
      </div>
      <div class="auth-tip"><Lightbulb :size="15" /><span>每一次专注，都会让下一个问题变得更容易。</span></div>
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
