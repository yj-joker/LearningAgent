export type CourseType = 'PRIVATE' | 'PENDING' | 'PUBLISHED'
export type SessionStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELED'
export type UserRole = 'USER' | 'ADMIN'
export type ApiId = string | number

export interface ApiResult<T> {
  code: string
  message: string
  data: T
}

export interface UserVO {
  username: string
  avatarUrl: string | null
  token: string | null
  role?: UserRole | null
}

export interface UserCredentials {
  username: string
  password: string
  avatarUrl?: string | null
}

export interface PageResult<T> {
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasPrevious: boolean
  hasNext: boolean
  items: T[]
}

export interface UserPageItem {
  id: string
  username: string
  avatarUrl: string | null
  role: UserRole
  createdAt: string
  updatedAt: string
}

export interface UserPageQuery {
  page: number
  size: number
  username?: string
  role?: UserRole
  createdAtStart?: string
  createdAtEnd?: string
}

export interface CourseCreatePayload {
  courseName: string
  difficultyLevel: number
  learningOutline: string | null
}

export interface CourseVO {
  id: ApiId
  /** 兼容早期后端响应，当前后端使用 id。 */
  courseId?: ApiId
  courseName: string
  publisherId: ApiId
  difficultyLevel: number
  learningOutline: string | null
  courseType: CourseType
  createdAt: string
  updatedAt: string
}

export interface ChapterPayload {
  id?: ApiId
  title: string
  courseId: ApiId
  sortOrder: number
}

export interface ChapterVO {
  id: string
  title: string
  sortOrder: number
}

export interface SessionCreatePayload {
  courseId: ApiId
  sessionTitle: string
}

export interface LearningSessionVO {
  sessionTitle: string
  sessionStatus: SessionStatus
}

export type ActivityKind = 'course-created' | 'course-published' | 'session-created' | 'session-completed'

export interface ActivityRecord {
  id: string
  kind: ActivityKind
  title: string
  description: string
  createdAt: string
  status: CourseType | SessionStatus
  resourceId?: string
}

export interface KnownCourse {
  courseId: string
  courseName: string
  courseType: CourseType
  updatedAt: string
}
