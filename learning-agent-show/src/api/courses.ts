import { request } from './client'
import type { CourseCreatePayload, CourseVO } from '@/types/api'

const COURSE_BASE = '/learning-agent/courses'

export function createCourse(payload: CourseCreatePayload) {
  return request<CourseVO>(`${COURSE_BASE}/createCourse`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function publishCourse(courseId: number) {
  return request<CourseVO>(`${COURSE_BASE}/publishCourse/${courseId}`, {
    method: 'PUT',
  })
}
