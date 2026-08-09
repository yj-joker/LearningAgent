<script setup lang="ts">
import { ref } from 'vue'
import {
  ArrowRight,
  Ban,
  BookCheck,
  CheckCircle2,
  Clock3,
  LockKeyhole,
  ShieldCheck,
} from 'lucide-vue-next'
import { passCourse, rejectCourse } from '@/api/courses'
import { ApiError } from '@/api/client'
import StatusBadge from '@/components/StatusBadge.vue'
import { useToast } from '@/composables/useToast'
import type { CourseVO } from '@/types/api'

type ReviewAction = 'pass' | 'reject'

const { showToast } = useToast()
const courseId = ref('')
const courseIdError = ref('')
const submitting = ref<ReviewAction | null>(null)
const lastResult = ref<CourseVO | null>(null)

function validateCourseId() {
  const value = courseId.value.trim()
  courseIdError.value = /^[1-9]\d*$/.test(value) ? '' : '请输入有效的正整数课程 ID'
  return courseIdError.value ? null : value
}

async function changeStatus(action: ReviewAction) {
  const id = validateCourseId()
  if (!id) return

  submitting.value = action
  try {
    const result = action === 'pass' ? await passCourse(id) : await rejectCourse(id)
    lastResult.value = result
    showToast(
      'success',
      action === 'pass' ? '课程审核通过' : '课程已驳回或下架',
      `${result.courseName || `课程 #${id}`} 当前状态：${result.courseType}`,
    )
  } catch (error) {
    showToast(
      'error',
      action === 'pass' ? '审核通过失败' : '驳回或下架失败',
      error instanceof ApiError ? error.message : '发生未知错误',
    )
  } finally {
    submitting.value = null
  }
}
</script>

<template>
  <div class="admin-courses-view">
    <header class="admin-page-heading">
      <div>
        <span class="admin-eyebrow"><ShieldCheck :size="14" /> COURSE GOVERNANCE</span>
        <h1>课程审核</h1>
        <p>根据课程 ID 执行审核、驳回和下架操作，确保公开课程经过管理员确认。</p>
      </div>
      <span class="admin-workflow-pill"><BookCheck :size="16" /> 三阶段状态流转</span>
    </header>

    <section class="admin-course-flow" aria-label="课程状态流程">
      <article>
        <span class="flow-icon flow-private"><LockKeyhole :size="20" /></span>
        <div><small>创建完成</small><strong>PRIVATE</strong><p>课程仅创建者可见</p></div>
      </article>
      <ArrowRight class="flow-arrow" :size="19" />
      <article>
        <span class="flow-icon flow-pending"><Clock3 :size="20" /></span>
        <div><small>用户提交审核</small><strong>PENDING</strong><p>等待管理员处理</p></div>
      </article>
      <ArrowRight class="flow-arrow" :size="19" />
      <article>
        <span class="flow-icon flow-published"><CheckCircle2 :size="20" /></span>
        <div><small>管理员审核通过</small><strong>PUBLISHED</strong><p>其他学习者可访问</p></div>
      </article>
    </section>

    <section class="admin-review-card">
      <div class="admin-review-intro">
        <span class="section-kicker">COURSE ID</span>
        <h2>定位需要处理的课程</h2>
        <p>当前后端没有课程列表或待审核列表接口，因此管理端只能使用课程 ID 发起状态变更。</p>
      </div>
      <div class="admin-course-id-field">
        <label for="admin-course-id">课程 ID</label>
        <input
          id="admin-course-id"
          v-model="courseId"
          inputmode="numeric"
          placeholder="例如：1001"
          @input="courseIdError = ''"
        >
        <span v-if="courseIdError" class="field-error">{{ courseIdError }}</span>
      </div>

      <div class="admin-review-actions">
        <article class="review-action review-pass">
          <span><CheckCircle2 :size="23" /></span>
          <div>
            <small>PENDING → PUBLISHED</small>
            <h3>审核通过</h3>
            <p>仅待审核课程可以通过；通过后，其他用户可以创建该课程的学习会话。</p>
          </div>
          <button :disabled="submitting !== null" @click="changeStatus('pass')">
            {{ submitting === 'pass' ? '处理中…' : '确认通过' }}
            <ArrowRight v-if="submitting !== 'pass'" :size="16" />
          </button>
        </article>

        <article class="review-action review-reject">
          <span><Ban :size="23" /></span>
          <div>
            <small>PENDING / PUBLISHED → PRIVATE</small>
            <h3>驳回或下架</h3>
            <p>待审核课程会被驳回，已发布课程会被下架；两种操作都会恢复为私有状态。</p>
          </div>
          <button :disabled="submitting !== null" @click="changeStatus('reject')">
            {{ submitting === 'reject' ? '处理中…' : '驳回 / 下架' }}
            <ArrowRight v-if="submitting !== 'reject'" :size="16" />
          </button>
        </article>
      </div>
    </section>

    <section v-if="lastResult" class="admin-review-result" aria-live="polite">
      <span><BookCheck :size="21" /></span>
      <div>
        <small>最近一次操作结果</small>
        <strong>{{ lastResult.courseName || `课程 #${lastResult.id}` }}</strong>
        <p>课程 ID：{{ lastResult.id }}</p>
      </div>
      <StatusBadge :status="lastResult.courseType" />
    </section>
  </div>
</template>
