<script setup lang="ts">
import { computed } from 'vue'
import { ChevronDown, ListChecks, RefreshCw } from 'lucide-vue-next'
import type { CourseLearningProgress, SessionGoalProgress } from '@/types/api'

const props = defineProps<{
  mode: 'FOCUS' | 'COURSE'
  focus: SessionGoalProgress | null
  course: CourseLearningProgress | null
  loading: boolean
  error: string
  readOnly: boolean
}>()
defineEmits<{ retry: [] }>()

const steps = computed(() => props.focus?.steps ?? [])
const points = computed(() => props.course?.points ?? [])
const hasProgress = computed(() => props.mode === 'COURSE' ? Boolean(props.course) : Boolean(props.focus))
// 数量只根据后端状态计算；取消或移除的记录保留在详情，但不进入学习总数。
const total = computed(() => props.mode === 'COURSE'
  ? points.value.filter(point => point.status !== 'REMOVED').length
  : steps.value.filter(step => step.status !== 'CANCELED').length)
const confirmed = computed(() => props.mode === 'COURSE'
  ? points.value.filter(point => point.status === 'CONFIRMED').length
  : steps.value.filter(step => step.status === 'COMPLETED').length)
const currentPoint = computed(() => points.value.find(point => String(point.knowledgePointId) === String(props.course?.currentKnowledgePointId)))
const runningStep = computed(() => steps.value.find(step => step.status === 'IN_PROGRESS'))
const waitingStep = computed(() => steps.value.find(step => step.status === 'BLOCKED' || step.status === 'PENDING'))
const goal = computed(() => props.mode === 'COURSE' ? props.course?.courseName : props.focus?.goal)
const currentText = computed(() => {
  if (props.mode === 'COURSE') {
    if (currentPoint.value) return `当前知识点：${currentPoint.value.knowledgePointName}`
    return props.course?.completed ? '本次课程快照已完成' : '当前知识点尚未指定'
  }
  if (runningStep.value) return `当前步骤：${runningStep.value.description}`
  if (waitingStep.value) return `${waitingStep.value.status === 'BLOCKED' ? '受阻步骤' : '待开始步骤'}：${waitingStep.value.description}`
  return steps.value.length ? '当前没有进行中的步骤' : '步骤尚未制定'
})

const statusLabels: Record<string, string> = {
  PENDING: '待开始', NOT_STARTED: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已确认完成',
  CONFIRMED: '已确认', BLOCKED: '受阻', CANCELED: '已取消', REVIEW_REQUIRED: '需要复核', REMOVED: '已移除',
}
const evidenceLabels: Record<string, string> = { EXPLANATION: '自己的解释', EXERCISE: '独立练习', BOTH: '解释与独立练习' }
</script>

<template>
  <details class="learning-progress-card">
    <summary>
      <ListChecks :size="18" class="progress-icon" />
      <div class="progress-summary-copy">
        <strong>{{ goal || (mode === 'COURSE' ? '课程学习进度' : '本次目标进度') }}</strong>
        <span v-if="loading">正在读取已保存进度…</span>
        <span v-else-if="error" class="progress-summary-error">进度读取失败，展开后可重试</span>
        <span v-else-if="hasProgress">{{ currentText }}</span>
        <span v-else>{{ mode === 'COURSE' ? '暂无已加载的课程进度' : '本次目标尚未制定' }}</span>
      </div>
      <span v-if="hasProgress" class="progress-count">已确认 {{ confirmed }}/{{ total }} {{ mode === 'COURSE' ? '知识点' : '步骤' }}</span>
      <span v-if="course?.courseContentChanged" class="progress-change-tag">课程有变化</span>
      <ChevronDown :size="16" class="progress-chevron" />
    </summary>
    <div class="progress-details">
      <p class="progress-source">{{ readOnly ? '会话已完成，仅查看已保存进度。' : '以下进度来自系统已保存的状态。' }}</p>
      <p v-if="error" class="progress-error" role="status">{{ error }} <button type="button" :disabled="loading" @click="$emit('retry')"><RefreshCw :size="13" /> 重试</button></p>
      <p v-if="mode === 'FOCUS' && !focus && !loading && !error">{{ readOnly ? '当前会话没有已保存的目标快照。' : '描述本次学习目标后，助教会制定步骤；保存后会在这里展示。' }}</p>
      <p v-if="mode === 'COURSE' && !course && !loading && !error">查看历史不会创建课程进度。{{ readOnly ? '当前会话没有已保存的课程快照。' : '发送课程学习问题后，系统会开始记录本次进度。' }}</p>
      <p v-if="course?.courseContentChanged" class="progress-course-change">源课程内容已有变化，当前显示的是已保存的学习快照；旧进度不能代表新版课程已完成。</p>
      <p v-if="mode === 'FOCUS' && focus?.constraints"><b>限制条件</b> {{ focus.constraints }}</p>
      <div v-if="mode === 'FOCUS' && focus" class="progress-items">
        <article v-for="step in steps" :key="step.stepRef" :class="{ 'progress-item-current': step.status === 'IN_PROGRESS' }">
          <header><strong>{{ step.position }}. {{ step.description }}</strong><span :class="['progress-status', `progress-${step.status.toLowerCase()}`]">{{ statusLabels[step.status] ?? '未知状态' }}</span></header>
          <p v-if="step.completionCriteria"><b>完成条件</b> {{ step.completionCriteria }}</p>
          <p v-if="step.resultSummary"><b>结果记录</b> {{ step.resultSummary }}</p>
        </article>
        <p v-if="!steps.length">目标已保存，尚未制定步骤。</p>
        <small>步骤完成记录不直接代表知识掌握程度。</small>
      </div>
      <div v-else-if="mode === 'COURSE' && course" class="progress-items">
        <article v-for="point in points" :key="String(point.knowledgePointId)" :class="{ 'progress-item-current': String(point.knowledgePointId) === String(course.currentKnowledgePointId) }">
          <header><strong>{{ point.knowledgePointName }}</strong><span :class="['progress-status', `progress-${point.status.toLowerCase()}`]">{{ statusLabels[point.status] ?? '未知状态' }}</span></header>
          <small>{{ point.chapterTitle }}</small>
          <p v-if="point.knowledgePointDescription"><b>学习内容</b> {{ point.knowledgePointDescription }}</p>
          <p v-if="point.evidenceType"><b>依据类型</b> {{ evidenceLabels[point.evidenceType] ?? point.evidenceType }}</p>
          <p v-if="point.evidenceSummary"><b>学习依据</b> {{ point.evidenceSummary }}</p>
          <p v-if="point.assessmentReason"><b>判断理由</b> {{ point.assessmentReason }}</p>
        </article>
        <p v-if="!points.length">当前课程快照还没有知识点。</p>
      </div>
    </div>
  </details>
</template>

<style scoped>
.learning-progress-card { flex: 0 0 auto; border-bottom: 1px solid #dce7e1; background: #f4f8f5; color: #37564a; }
summary { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 12px 18px; cursor: pointer; list-style: none; }
summary::-webkit-details-marker { display: none; }
.progress-icon, .progress-chevron { flex: 0 0 auto; }
.progress-summary-copy { display: grid; min-width: 0; flex: 1; gap: 4px; }
.progress-summary-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.progress-summary-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #70847b; font-size: 11px; }
.progress-summary-copy .progress-summary-error { color: #a75742; }
.progress-count { flex: 0 0 auto; color: #487363; font-size: 11px; white-space: nowrap; }
.progress-change-tag { flex: 0 0 auto; padding: 3px 6px; border-radius: 4px; background: #fff0c6; color: #886b2f; font-size: 10px; }
.progress-chevron { transition: transform 160ms ease; }
details[open] .progress-chevron { transform: rotate(180deg); }
.progress-details { max-height: 290px; padding: 0 18px 14px; overflow-y: auto; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.progress-details p { margin: 8px 0; }
.progress-source, .progress-items small { color: #7c8e84; font-size: 11px; }
.progress-error { color: #a75742; }
.progress-error button { display: inline-flex; align-items: center; gap: 4px; padding: 3px 7px; color: #487363; border: 1px solid #c5d8ce; border-radius: 4px; background: white; cursor: pointer; }
.progress-course-change { padding: 8px 10px; background: #fff5db; color: #886b2f; border-radius: 4px; }
.progress-items article { margin-top: 10px; padding: 10px 12px; border: 1px solid #e0e9e3; border-radius: 5px; background: white; }
.progress-items article.progress-item-current { border-color: #a7cdbc; }
.progress-items header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.progress-items header strong { font-size: 12px; }
.progress-status { flex: 0 0 auto; color: #7f8e85; font-size: 11px; }
.progress-confirmed, .progress-completed { color: #31775d; }
.progress-blocked, .progress-review_required { color: #9a6c27; }
.progress-in_progress { color: #287774; }
.progress-items b, .progress-details > p b { margin-right: 5px; color: #637d70; font-weight: 500; }
@media (max-width: 620px) { summary { padding: 9px 12px; gap: 7px; flex-wrap: wrap; } .progress-summary-copy { flex-basis: calc(100% - 50px); } .progress-count { margin-left: 25px; } .progress-chevron { margin-left: auto; } .progress-details { max-height: 220px; padding-inline: 12px; } }
</style>
