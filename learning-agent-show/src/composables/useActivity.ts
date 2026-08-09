import { computed, ref } from 'vue'
import type { ActivityRecord, KnownCourse } from '@/types/api'

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
  const knownCourses = computed<KnownCourse[]>(() => {
    const courses = new Map<string, KnownCourse>()
    for (const activity of activities.value) {
      if (!activity.kind.includes('course') || !activity.resourceId || courses.has(activity.resourceId)) continue
      if (activity.status !== 'PRIVATE' && activity.status !== 'PENDING' && activity.status !== 'PUBLISHED') continue
      courses.set(activity.resourceId, {
        courseId: activity.resourceId,
        courseName: activity.title,
        courseType: activity.status,
        updatedAt: activity.createdAt,
      })
    }
    return [...courses.values()]
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

  return { activities, recentActivities, courseCount, activeSessionCount, knownCourses, addActivity, clearActivities }
}
