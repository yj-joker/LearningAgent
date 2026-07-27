export type CourseType = 'PUBLIC' | 'PRIVATE'
export type SessionStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELED'

export interface ApiResult<T> {
  code: string
  message: string
  data: T
}

export interface UserVO {
  username: string
  avatarUrl: string | null
  token: string | null
}

export interface UserCredentials {
  username: string
  password: string
  avatarUrl?: string | null
}

export interface CourseCreatePayload {
  courseName: string
  difficultyLevel: number
  learningOutline: string | null
  courseType: CourseType
}

export interface CourseVO {
  courseName: string
  publisherName: string | null
  difficultyLevel: number
  learningOutline: string | null
  courseType: CourseType
}

export interface SessionCreatePayload {
  courseId: number
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
}
