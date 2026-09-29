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

export interface KnowledgePointPayload {
  id?: ApiId
  courseId: ApiId
  chapterId: ApiId
  name: string
  sortOrder: number
  description: string | null
}

export interface KnowledgePointVO {
  id: string
  name: string
  sortOrder: number
  description: string | null
  createdAt: string
  updatedAt: string
}

export type KnowledgePointRelationType = 'PREREQUISITE' | 'CONFUSABLE'
export type KnowledgePointRelationStatus = 'PENDING' | 'ACTIVE' | 'REJECTED' | 'DEPRECATED'
export type KnowledgePointRelationSource = 'ADMIN' | 'USER_SUGGESTED' | 'AI_GENERATED'

export interface KnowledgePointRelationPayload {
  id?: ApiId | null
  fromPointId: ApiId
  toPointId: ApiId
  relationType: KnowledgePointRelationType
}

export interface KnowledgePointRelationVO {
  id: ApiId
  fromPointId: ApiId
  toPointId: ApiId
  relationType: KnowledgePointRelationType
  status: KnowledgePointRelationStatus
  source: KnowledgePointRelationSource
  createdAt: string
}

export type KnowledgeBaseOwnerType = 'USER' | 'SYSTEM'
export type KnowledgeBaseVisibility = 'PUBLIC' | 'PRIVATE'
export type DocumentStatus = 'UPLOADED' | 'PARSING' | 'READY' | 'FAILED'

export interface KnowledgeBaseCreatePayload {
  courseId: ApiId
  name: string
  description?: string | null
}

export interface KnowledgeBaseVO {
  id: ApiId
  name: string
  description: string | null
  ownerType: KnowledgeBaseOwnerType
  visibility: KnowledgeBaseVisibility
  createdAt: string
  updatedAt: string
}

export interface DocumentVO {
  id: ApiId
  filename: string
  objectName: string
  fileSize: number
  status: DocumentStatus
  mimeType: string | null
  updatedAt: string
}

export interface SessionCreatePayload {
  courseId: ApiId
  sessionTitle: string
}

export interface LearningSessionVO {
  id: ApiId
  courseId?: ApiId
  sessionTitle: string
  sessionStatus: SessionStatus
  createAt?: string | null
  updateAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentChatPayload {
  sessionId: ApiId
  userMessage: string
}

// 同一 runId 的申请来自一次模型响应；内容只是提案，尚未写入记忆。
export interface MemoryApprovalView {
  id: ApiId
  sessionId: ApiId
  // 整批整理不是单条更新，页面根据类型读取对应的方案结构。
  approvalType: 'CHANGE' | 'CONSOLIDATION'
  operation: 'CREATE' | 'UPDATE' | 'DELETE' | null
  scope: 'USER' | 'SESSION'
  candidateJson: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'STALE'
  decisionReason: string | null
}

// 待审批是正常的业务状态，不应显示成请求失败或宣称操作已经完成。
export interface AgentRunResult {
  runId: string
  batchNumber: number
  status: 'COMPLETED' | 'WAITING_APPROVAL' | 'APPROVAL_RESOLVED' | 'RUNNING' | 'FAILED'
  answer: string
  approvals: ToolApprovalRequest[]
}

// 通用工具审批；工具原参数只展示，用户只能提交决定，不能修改它。
export interface ToolApprovalRequest {
  runId: string
  batchNumber: number
  toolCallId: string
  toolName: string
  arguments: string
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  decisionReason: string | null
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
