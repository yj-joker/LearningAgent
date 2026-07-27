<script setup lang="ts">
import { BookOpenCheck } from 'lucide-vue-next'

defineProps<{ title: string; description: string }>()
</script>

<template>
  <div class="empty-state">
    <span><BookOpenCheck :size="28" /></span>
    <strong>{{ title }}</strong>
    <p>{{ description }}</p>
  </div>
</template>
