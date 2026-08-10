import { computed, ref } from 'vue'
import type { ActivityRecord, KnownCourse } from '@/types/api'
import { readStoredUser } from '@/utils/authStorage'

const LEGACY_STORAGE_KEY = 'learning-agent.activities.v1'
const STORAGE_PREFIX = 'learning-agent.activities.v2'

function currentOwner() {
  return readStoredUser()?.username || 'guest'
}

function storageKey(owner: string) {
  return `${STORAGE_PREFIX}.${encodeURIComponent(owner)}`
}

function loadActivities(owner: string): ActivityRecord[] {
  try {
    let value = localStorage.getItem(storageKey(owner))
    const legacy = localStorage.getItem(LEGACY_STORAGE_KEY)
    if (!value && legacy && owner !== 'guest') {
      value = legacy
      localStorage.setItem(storageKey(owner), legacy)
      localStorage.removeItem(LEGACY_STORAGE_KEY)
    }
    return value ? JSON.parse(value) as ActivityRecord[] : []
  } catch {
    return []
  }
}

let activityOwner = currentOwner()
const activities = ref<ActivityRecord[]>(loadActivities(activityOwner))

function syncOwner() {
  const owner = currentOwner()
  if (owner === activityOwner) return
  activityOwner = owner
  activities.value = loadActivities(activityOwner)
}

function persist() {
  localStorage.setItem(storageKey(activityOwner), JSON.stringify(activities.value.slice(0, 30)))
}

export function useActivity() {
  syncOwner()
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
