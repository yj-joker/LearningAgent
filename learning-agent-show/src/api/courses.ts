import { request } from './client'
import type { CourseCreatePayload, CourseVO } from '@/types/api'

const COURSE_BASE = '/learning-agent/courses'

export function createCourse(payload: CourseCreatePayload) {
  return request<CourseVO>(`${COURSE_BASE}/createCourse`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function publishCourse(courseId: string | number) {
  return request<CourseVO>(`${COURSE_BASE}/publishCourse/${encodeURIComponent(String(courseId))}`, {
    method: 'PUT',
  })
}

export function passCourse(courseId: string | number) {
  return request<CourseVO>(`${COURSE_BASE}/passCourse/${encodeURIComponent(String(courseId))}`, {
    method: 'PUT',
  })
}

export function rejectCourse(courseId: string | number) {
  return request<CourseVO>(`${COURSE_BASE}/rejectCourse/${encodeURIComponent(String(courseId))}`, {
    method: 'PUT',
  })
}
