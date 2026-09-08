import { request, requestBlob } from './client'
import type { ApiId, DocumentVO } from '@/types/api'

export function uploadDocument(kbId: ApiId, file: File) {
  const form = new FormData()
  form.append('file', file)
  return request<DocumentVO>(`/document/upload/${encodeURIComponent(String(kbId))}`, { method: 'POST', body: form })
}

export async function downloadDocument(documentId: ApiId, filename?: string) {
  const result = await requestBlob(`/document/download/${encodeURIComponent(String(documentId))}`)
  const objectUrl = URL.createObjectURL(result.blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = result.filename || filename || 'document-download'
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
