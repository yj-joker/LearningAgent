<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { CalendarDays, Menu, Sparkles } from 'lucide-vue-next'

defineEmits<{ openMenu: [] }>()

const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? '概览'))
const today = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short',
}).format(new Date())
</script>

<template>
  <header class="topbar">
    <div class="topbar-title">
      <button class="icon-button menu-button" aria-label="打开菜单" @click="$emit('openMenu')">
        <Menu :size="21" />
      </button>
      <div>
        <span class="eyebrow"><Sparkles :size="13" /> LEARNING WORKSPACE</span>
        <h1>{{ pageTitle }}</h1>
      </div>
    </div>
    <div class="today-pill">
      <CalendarDays :size="17" />
      <span>{{ today }}</span>
    </div>
  </header>
</template>
