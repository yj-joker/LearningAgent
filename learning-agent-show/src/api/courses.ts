import { request } from './client'
import type { ApiId, CourseCreatePayload, CourseVO } from '@/types/api'

const COURSE_BASE = '/learning-agent/courses'

type CourseApiResponse = Omit<CourseVO, 'id' | 'courseId'> & { id?: ApiId; courseId?: ApiId }

function normalizeCourse(course: CourseApiResponse): CourseVO {
  return {
    ...course,
    id: course.id ?? course.courseId ?? '',
    courseId: course.courseId,
  }
}

export async function createCourse(payload: CourseCreatePayload) {
  const course = await request<CourseApiResponse>(`${COURSE_BASE}/createCourse`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
  return normalizeCourse(course)
}

export async function publishCourse(courseId: string | number) {
  const course = await request<CourseApiResponse>(`${COURSE_BASE}/publishCourse/${encodeURIComponent(String(courseId))}`, {
    method: 'PATCH',
  })
  return normalizeCourse(course)
}

export async function passCourse(courseId: string | number) {
  const course = await request<CourseApiResponse>(`${COURSE_BASE}/passCourse/${encodeURIComponent(String(courseId))}`, {
    method: 'PATCH',
  })
  return normalizeCourse(course)
}

export async function rejectCourse(courseId: string | number) {
  const course = await request<CourseApiResponse>(`${COURSE_BASE}/rejectCourse/${encodeURIComponent(String(courseId))}`, {
    method: 'PATCH',
  })
  return normalizeCourse(course)
}
