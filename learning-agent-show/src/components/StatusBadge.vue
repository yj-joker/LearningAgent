<script setup lang="ts">
import { computed } from 'vue'
import type { CourseType, SessionStatus } from '@/types/api'

const props = defineProps<{ status: CourseType | SessionStatus }>()

const labels: Record<CourseType | SessionStatus, string> = {
  PUBLIC: '已公开',
  PRIVATE: '私有',
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  CANCELED: '已取消',
}
const label = computed(() => labels[props.status])
</script>

<template>
  <span class="status-badge" :class="`status-${status.toLowerCase()}`">
    <i />{{ label }}
  </span>
</template>
