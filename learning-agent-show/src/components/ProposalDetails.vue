<script setup lang="ts">
import { computed } from 'vue'
import JSONbigFactory from 'json-bigint'

const props = defineProps<{ value: unknown; depth?: number }>()
const parser = JSONbigFactory({ storeAsString: true })
// 用结构化解析保留大整数引用；这里只改变展示，不生成执行参数。
const parsed = computed<unknown>(() => {
  if (typeof props.value !== 'string') return props.value
  try { return parser.parse(props.value) }
  catch { return props.value }
})
const labels: Record<string, string> = {
  scope: '记忆范围', operation: '变更操作', targetMemoryRefs: '涉及的记忆', userEvidence: '用户原话依据',
  memoryKey: '记忆标识', memoryTopic: '主题', memorySummary: '摘要', memoryContent: '记忆内容',
  merges: '合并方案', conflicts: '存在冲突的记忆（保留原内容）', keepRef: '保留的记忆', sourceRefs: '合并来源',
  title: '标题', objective: '学习目标', learnerProfile: '当前基础', weeklyCommitment: '每周投入',
  constraints: '限制条件', steps: '学习步骤', description: '学习内容', completionCriteria: '完成条件',
  draftRef: '计划引用', expectedVersion: '预期版本', stepRef: '步骤引用', sessionId: '来源会话',
  content: '内容', reason: '申请原因', goalRef: '目标引用', taskRef: '任务引用', status: '状态',
  progress: '学习进度', evidence: '完成依据', goal: '目标', plan: '执行计划', tasks: '任务',
  updates: '步骤进度变更', targetStatus: '拟变更为', currentStatus: '当前状态', previousStatus: '原状态',
  evidenceType: '证据类型', evidenceSummary: '学习证据摘要', assessmentReason: '判断理由（待确认）',
  completionBasis: '完成依据类型', learningScope: '学习范围', learningPlanScope: '学习计划范围',
  pointRef: '知识点引用', learningPlanStageRef: '计划阶段引用', memoryRef: '记忆引用',
  currentGoalRef: '当前目标引用', recoveryRef: '原文恢复引用', toolCallId: '工具调用编号',
  planVersion: '计划版本', evaluatedPlanVersion: '评估时的计划版本',
  evaluatedSemanticVersion: '评估时的内容版本', expectedProgressVersion: '预期进度版本',
  expectedBindingVersion: '预期关联版本', semanticVersion: '内容版本', bindingVersion: '关联版本',
  version: '版本', position: '步骤顺序', sortOrder: '排序值', goalNumber: '目标序号',
  courseId: '课程编号', chapterId: '章节编号', knowledgePointId: '知识点编号',
  courseName: '课程', chapterName: '章节', knowledgePointName: '知识点',
  name: '名称', username: '用户名', mode: '学习模式', approved: '是否同意',
  decisionReason: '审批说明', conflictsReason: '冲突原因', current: '是否当前目标',
  goals: '学习目标', memoryTargets: '涉及的记忆', sourceMemoryRefs: '来源记忆引用',
  offset: '原文起始位置', limit: '原文读取长度', expectedSyncToken: '预期同步凭据',
}
// 正文、状态和依据先展示；引用与版本只收进折叠区，不丢弃任何申请字段。
const priorityKeys = ['title', 'courseName', 'chapterName', 'knowledgePointName', 'memoryTopic',
  'goal', 'objective', 'memorySummary', 'memoryContent', 'content', 'description', 'steps', 'updates',
  'merges', 'conflicts', 'currentStatus', 'previousStatus', 'targetStatus', 'status', 'completionCriteria',
  'evidenceSummary', 'userEvidence', 'assessmentReason', 'reason', 'evidenceType', 'completionBasis']
const technicalKeys = new Set(['memoryKey', 'position', 'sortOrder', 'goalNumber', 'offset', 'limit', 'expectedSyncToken'])
const isObject = computed(() => parsed.value !== null && typeof parsed.value === 'object' && !Array.isArray(parsed.value))
const entries = computed(() => isObject.value ? Object.entries(parsed.value as Record<string, unknown>) : [])
function isTechnical(key: string) {
  return technicalKeys.has(key) || /(?:refs?|ids?|versions?)$/i.test(key)
}
const contentEntries = computed(() => entries.value.filter(([key]) => !isTechnical(key)).sort(([left], [right]) => {
  const leftIndex = priorityKeys.indexOf(left)
  const rightIndex = priorityKeys.indexOf(right)
  return (leftIndex < 0 ? priorityKeys.length : leftIndex) - (rightIndex < 0 ? priorityKeys.length : rightIndex)
}))
const technicalEntries = computed(() => entries.value.filter(([key]) => isTechnical(key)))
const translatedFields = new Set(['operation', 'scope', 'status', 'targetStatus', 'currentStatus', 'previousStatus',
  'evidenceType', 'completionBasis', 'learningScope', 'learningPlanScope', 'mode'])
const values: Record<string, string> = {
  CREATE: '新增', UPDATE: '修改', DELETE: '删除', USER: '长期记忆', SESSION: '当前会话',
  PENDING: '待确认', APPROVED: '已同意', REJECTED: '已拒绝', STALE: '已失效',
  ACTIVE: '已生效', DRAFT: '未生效', ARCHIVED: '已归档',
  NOT_STARTED: '未开始', IN_PROGRESS: '进行中', CONFIRMED: '已确认掌握',
  REVIEW_REQUIRED: '需要复核', REMOVED: '已移除', COMPLETED: '已完成', BLOCKED: '遇到阻碍',
  CANCELED: '已取消', WAITING_APPROVAL: '等待审批', APPROVAL_RESOLVED: '待继续执行',
  RUNNING: '执行中', FAILED: '执行失败', EXPLANATION: '解释证据', EXERCISE: '练习证据',
  BOTH: '解释与练习证据', DIALOGUE_EVIDENCE: '用户对话依据', USER_CONFIRMED: '用户明确确认',
  NOT_APPLICABLE: '不适用', CURRENT_STAGE: '当前计划阶段', OTHER_STAGE: '其他计划阶段',
  OUT_OF_PLAN: '计划之外', CHAT: '自由问答', FOCUS: '专注学习', COURSE: '课程学习',
}
function fieldLabel(key: string) {
  return Object.prototype.hasOwnProperty.call(labels, key) ? labels[key] : key
}
// 只翻译指定字段的枚举，正文和用户原话即使包含同样的英文也保持原样。
function display(value: unknown, key?: string) {
  if (value === true) return '是'
  if (value === false) return '否'
  if (value === null || value === undefined) return '未提供'
  if (value === '') return '空文本'
  const text = String(value)
  return key && translatedFields.has(key) && Object.prototype.hasOwnProperty.call(values, text) ? values[text] : text
}
// 深层数据提供完整只读文本兜底，不把对象误显示成 [object Object]。
function nestedText(value: unknown) {
  try { return JSON.stringify(value, null, 2) ?? '未提供' }
  catch { return String(value) }
}
</script>

<template>
  <div class="proposal-details">
    <details v-if="(depth ?? 0) > 8" class="proposal-technical"><summary>深层内容</summary><pre>{{ nestedText(parsed) }}</pre></details>
    <ol v-else-if="Array.isArray(parsed)" class="proposal-list">
      <li v-for="(item, index) in parsed" :key="index"><ProposalDetails :value="item" :depth="(depth ?? 0) + 1" /></li>
      <li v-if="!parsed.length" class="proposal-empty">无</li>
    </ol>
    <template v-else-if="isObject">
      <dl v-if="contentEntries.length" class="proposal-fields">
        <div v-for="[key, value] in contentEntries" :key="key">
          <dt>{{ fieldLabel(key) }}</dt>
          <dd><ProposalDetails v-if="value !== null && typeof value === 'object'" :value="value" :depth="(depth ?? 0) + 1" /><span v-else>{{ display(value, key) }}</span></dd>
        </div>
      </dl>
      <details v-if="technicalEntries.length" class="proposal-technical">
        <summary>技术详情 <span>{{ technicalEntries.length }} 项</span></summary>
        <dl class="proposal-fields">
          <div v-for="[key, value] in technicalEntries" :key="key">
            <dt>{{ fieldLabel(key) }}</dt>
            <dd><ProposalDetails v-if="value !== null && typeof value === 'object'" :value="value" :depth="(depth ?? 0) + 1" /><span v-else>{{ display(value, key) }}</span></dd>
          </div>
        </dl>
      </details>
      <p v-if="!entries.length" class="proposal-empty">无字段</p>
    </template>
    <p v-else class="proposal-text">{{ display(parsed) }}</p>
  </div>
</template>

<style scoped>
.proposal-details { min-width: 0; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.proposal-fields { display: grid; gap: 12px; margin: 0; }
.proposal-fields > div { display: grid; grid-template-columns: 115px minmax(0, 1fr); gap: 14px; }
dt { color: #74817d; font-size: 12px; } dd { margin: 0; color: #293a36; white-space: pre-wrap; }
.proposal-list { margin: 0; padding-left: 22px; }
.proposal-list > li + li { margin-top: 16px; padding-top: 12px; border-top: 1px solid #e7ecea; }
.proposal-text { margin: 0; white-space: pre-wrap; }
.proposal-empty { color: #74817d; }
.proposal-technical { margin-top: 14px; padding: 10px 12px; border: 1px solid #e2e9e5; border-radius: 6px; background: #f8faf9; }
.proposal-technical summary { cursor: pointer; color: #587168; font-size: 12px; }
.proposal-technical summary span { margin-left: 6px; color: #87938d; font-size: 11px; }
.proposal-technical[open] > summary { margin-bottom: 12px; }
.proposal-technical pre { margin: 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
@media (max-width: 620px) { .proposal-fields > div { grid-template-columns: 1fr; gap: 3px; } }
</style>
