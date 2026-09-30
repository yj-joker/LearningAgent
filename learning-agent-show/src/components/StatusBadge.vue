<script setup lang="ts">
import { computed } from 'vue'
import type { CourseType, SessionStatus } from '@/types/api'

type StatusValue = CourseType | SessionStatus
type DisplayStatus = StatusValue | 'UNKNOWN'

const props = defineProps<{ status?: StatusValue | null }>()

const labels: Record<StatusValue, string> = {
  PRIVATE: '私有',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  CANCELED: '已取消',
}
const normalizedStatus = computed<DisplayStatus>(() => {
  const status = props.status
  return status && Object.prototype.hasOwnProperty.call(labels, status)
    ? status
    : 'UNKNOWN'
})
const label = computed(() => normalizedStatus.value === 'UNKNOWN'
  ? '未知状态'
  : labels[normalizedStatus.value])
</script>

<template>
  <span class="status-badge" :class="`status-${normalizedStatus.toLowerCase()}`">
    <i />{{ label }}
  </span>
</template>
