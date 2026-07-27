import { computed, ref } from 'vue'
import type { ActivityRecord } from '@/types/api'

const STORAGE_KEY = 'learning-agent.activities.v1'

function loadActivities(): ActivityRecord[] {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value ? JSON.parse(value) as ActivityRecord[] : []
  } catch {
    return []
  }
}

const activities = ref<ActivityRecord[]>(loadActivities())

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(activities.value.slice(0, 30)))
}

export function useActivity() {
  const recentActivities = computed(() => activities.value.slice(0, 6))
  const courseCount = computed(() => activities.value.filter((item) => item.kind === 'course-created').length)
  const activeSessionCount = computed(() => {
    const created = activities.value.filter((item) => item.kind === 'session-created').length
    const completed = activities.value.filter((item) => item.kind === 'session-completed').length
    return Math.max(0, created - completed)
  })

  function addActivity(record: Omit<ActivityRecord, 'id' | 'createdAt'>) {
    activities.value.unshift({
      ...record,
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      createdAt: new Date().toISOString(),
    })
    persist()
  }

  function clearActivities() {
    activities.value = []
    persist()
  }

  return { activities, recentActivities, courseCount, activeSessionCount, addActivity, clearActivities }
}
