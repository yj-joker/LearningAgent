import { request } from './client'
import type { ApiId, ChapterPayload, ChapterVO } from '@/types/api'

type ChapterApiResponse = Omit<ChapterVO, 'id'> & { id: ApiId }

function normalizeChapters(chapters: ChapterApiResponse[]): ChapterVO[] {
  return chapters.map((chapter) => ({ ...chapter, id: String(chapter.id) }))
}

export async function createChapters(payload: ChapterPayload[]) {
  const chapters = await request<ChapterApiResponse[]>('/createChapters', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
  return normalizeChapters(chapters)
}

export async function getChaptersByCourseId(courseId: ApiId) {
  const chapters = await request<ChapterApiResponse[]>(`/getChaptersByCourseId/${encodeURIComponent(String(courseId))}`, {
    method: 'GET',
  })
  return normalizeChapters(chapters)
}

export async function updateChapters(payload: ChapterPayload[]) {
  const chapters = await request<ChapterApiResponse[]>('/updateChapters', {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
  return normalizeChapters(chapters)
}

export function deleteChaptersByIds(ids: ApiId[]) {
  const pathIds = ids.map((id) => encodeURIComponent(String(id))).join(',')
  return request<void>(`/deleteChaptersByIds/${pathIds}`, {
    method: 'DELETE',
  })
}
