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
}
const entries = computed(() => parsed.value && typeof parsed.value === 'object' && !Array.isArray(parsed.value)
  ? Object.entries(parsed.value).filter(([, value]) => value !== null && value !== undefined && value !== '') : [])
function display(value: unknown) {
  if (value === true) return '是'
  if (value === false) return '否'
  const text = String(value ?? '未提供')
  return ({ CREATE: '新增', UPDATE: '修改', DELETE: '删除', USER: '长期记忆', SESSION: '当前会话',
    PENDING: '待确认', APPROVED: '已同意', REJECTED: '已拒绝', ACTIVE: '已生效', DRAFT: '草案' } as Record<string, string>)[text] ?? text
}
</script>

<template>
  <div class="proposal-details">
    <template v-if="(depth ?? 0) > 8"><p>内容层级较多，请展开原始参数查看。</p></template>
    <ol v-else-if="Array.isArray(parsed)" class="proposal-list">
      <li v-for="(item, index) in parsed" :key="index"><ProposalDetails :value="item" :depth="(depth ?? 0) + 1" /></li>
      <li v-if="!parsed.length" class="proposal-empty">无</li>
    </ol>
    <dl v-else-if="entries.length" class="proposal-fields">
      <div v-for="[key, value] in entries" :key="key">
        <dt>{{ labels[key] ?? key }}</dt>
        <dd><ProposalDetails v-if="typeof value === 'object'" :value="value" :depth="(depth ?? 0) + 1" /><span v-else>{{ display(value) }}</span></dd>
      </div>
    </dl>
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
@media (max-width: 620px) { .proposal-fields > div { grid-template-columns: 1fr; gap: 3px; } }
</style>
