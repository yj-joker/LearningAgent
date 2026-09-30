<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { CalendarDays, Menu } from 'lucide-vue-next'

defineEmits<{ openMenu: [] }>()

const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? '概览'))
const now = new Date()
const today = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short',
}).format(now)
const todayDate = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
</script>

<template>
  <header class="topbar">
    <div class="topbar-title">
      <button class="icon-button menu-button" aria-label="打开菜单" @click="$emit('openMenu')">
        <Menu :size="21" />
      </button>
      <div>
        <h1>{{ pageTitle }}</h1>
      </div>
    </div>
    <time class="today-pill" :datetime="todayDate" aria-label="今天日期">
      <CalendarDays :size="17" />
      <span>{{ today }}</span>
    </time>
  </header>
</template>
