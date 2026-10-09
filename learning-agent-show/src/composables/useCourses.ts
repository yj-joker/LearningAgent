import { computed, ref, watch } from 'vue'
import { ApiError } from '@/api/client'
import { listCourses } from '@/api/courses'
import { useAuth } from '@/composables/useAuth'
import type { CourseVO, KnownCourse } from '@/types/api'

// 多个页面共用同一份数据库课程列表，避免浏览器历史缺少记录时无法选择课程。
const courseViews = ref<CourseVO[]>([])
const loading = ref(false)
const error = ref('')
const { currentUser } = useAuth()
let ownerToken: string | null = null
let requestVersion = 0
let editVersion = 0
const updatedCourses = new Map<string, { course: CourseVO; version: number }>()

const courses = computed<KnownCourse[]>(() => courseViews.value
  .filter(course => String(course.id ?? course.courseId ?? '') !== '')
  .map(course => ({
    courseId: String(course.id ?? course.courseId),
    courseName: course.courseName,
    courseType: course.courseType,
    updatedAt: course.updatedAt,
  })))

// 读取期间发生创建或修改时，保留更晚保存的课程，防止慢列表覆盖它。
async function loadCourses() {
  const token = currentUser.value?.token
  if (!token || token !== ownerToken) return
  const version = ++requestVersion
  const editsAtStart = editVersion
  loading.value = true
  error.value = ''
  try {
    const result = await listCourses()
    if (version !== requestVersion || token !== ownerToken || token !== currentUser.value?.token) return
    const merged = new Map(result.map(course => [String(course.id ?? course.courseId ?? ''), course]))
    for (const [id, update] of updatedCourses) {
      if (update.version > editsAtStart) merged.set(id, update.course)
    }
    courseViews.value = [...merged.values()].filter(course => String(course.id ?? course.courseId ?? '') !== '')
  } catch (cause) {
    if (version !== requestVersion || token !== ownerToken || token !== currentUser.value?.token) return
    error.value = cause instanceof ApiError ? cause.message : '课程加载失败，请稍后重试'
  } finally {
    if (version === requestVersion && token === ownerToken) loading.value = false
  }
}

// 保存接口成功后更新共享列表；调用方可传请求开始时的 token，防止换号后的旧结果写入。
function upsertCourse(course: CourseVO, requestToken: string | null | undefined = currentUser.value?.token) {
  if (!requestToken || requestToken !== ownerToken || requestToken !== currentUser.value?.token) return
  const id = String(course.id ?? course.courseId ?? '')
  if (!id) return
  editVersion++
  updatedCourses.set(id, { course, version: editVersion })
  const index = courseViews.value.findIndex(item => String(item.id ?? item.courseId) === id)
  if (index < 0) courseViews.value.unshift(course)
  else courseViews.value[index] = course
}

// 模块只建立一份账号监听；账号变化先清空旧课程，再读取新账号的数据。
watch(() => currentUser.value?.token, token => {
  ownerToken = token ?? null
  requestVersion++
  editVersion = 0
  updatedCourses.clear()
  courseViews.value = []
  loading.value = false
  error.value = ''
  if (token) void loadCourses()
}, { immediate: true, flush: 'sync' })

export function useCourses() {
  return { courses, courseViews: computed(() => courseViews.value), loading, error, loadCourses, upsertCourse }
}
