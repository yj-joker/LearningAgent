<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowRight, BookOpenText, ListTree, LockKeyhole, Plus, Send } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { createCourse, publishCourse } from '@/api/courses'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'
import type { KnownCourse } from '@/types/api'

const route = useRoute()
const router = useRouter()
const { knownCourses, addActivity } = useActivity()
const { showToast } = useToast()

const createOpen = ref(false)
const creating = ref(false)
const publishingCourseId = ref<string | null>(null)
const errors = reactive<Record<string, string>>({})
const form = reactive({ courseName: '', difficultyLevel: 3, learningOutline: '' })

watch(() => route.query.create, (value) => {
  if (value === '1') {
    createOpen.value = true
    router.replace({ query: {} })
  }
}, { immediate: true })

function validate() {
  errors.courseName = form.courseName.trim() ? '' : '请输入课程名称'
  errors.learningOutline = form.learningOutline.length > 2000 ? '大纲不能超过 2000 个字符' : ''
  return !errors.courseName && !errors.learningOutline
}

function resetForm() {
  form.courseName = ''
  form.difficultyLevel = 3
  form.learningOutline = ''
  Object.keys(errors).forEach((key) => { errors[key] = '' })
}

async function submitCreate() {
  if (!validate()) return
  creating.value = true
  try {
    const result = await createCourse({
      courseName: form.courseName.trim(),
      difficultyLevel: form.difficultyLevel,
      learningOutline: form.learningOutline.trim()
        ? JSON.stringify({ content: form.learningOutline.trim() })
        : null,
    })
    addActivity({
      kind: 'course-created',
      title: result.courseName,
      description: `难度 ${result.difficultyLevel}/5 · 私有课程`,
      status: result.courseType,
      resourceId: String(result.id),
    })
    showToast('success', '课程创建成功', `${result.courseName} 已加入你的课程`)
    createOpen.value = false
    resetForm()
  } catch (error) {
    showToast('error', '创建失败', error instanceof ApiError ? error.message : '发生未知错误')
  } finally {
    creating.value = false
  }
}

async function submitPublish(course: KnownCourse) {
  if (course.courseType !== 'PRIVATE' || publishingCourseId.value) return
  publishingCourseId.value = course.courseId
  try {
    const result = await publishCourse(course.courseId)
    addActivity({
      kind: 'course-published',
      title: result.courseName || course.courseName,
      description: '已提交审核',
      status: result.courseType,
      resourceId: course.courseId,
    })
    showToast('success', '已提交审核', `${result.courseName || course.courseName} 正在等待管理员处理`)
  } catch (error) {
    showToast('error', '提交审核失败', error instanceof ApiError ? error.message : '发生未知错误')
  } finally {
    publishingCourseId.value = null
  }
}

function statusDescription(course: KnownCourse) {
  if (course.courseType === 'PRIVATE') return '仅自己可见，可以继续编辑章节'
  if (course.courseType === 'PENDING') return '等待管理员审核'
  return '课程已发布'
}
</script>

<template>
  <div class="resource-view">
    <section class="page-heading">
      <div><span class="section-kicker">我的课程</span><h2>课程管理</h2><p>管理课程内容、章节和发布状态。</p></div>
      <button class="button button-primary" @click="createOpen = true"><Plus :size="18" /> 创建课程</button>
    </section>

    <section class="course-library-panel">
      <header class="resource-section-header">
        <div><h3>课程列表</h3><p>共 {{ knownCourses.length }} 门课程</p></div>
      </header>

      <div v-if="knownCourses.length" class="course-library-grid">
        <article v-for="course in knownCourses" :key="course.courseId" class="course-library-item">
          <header>
            <span class="course-library-icon"><BookOpenText :size="22" /></span>
            <StatusBadge :status="course.courseType" />
          </header>
          <div class="course-library-copy">
            <h3>{{ course.courseName }}</h3>
            <p>{{ statusDescription(course) }}</p>
            <time>最近更新 {{ new Date(course.updatedAt).toLocaleString('zh-CN') }}</time>
          </div>
          <footer>
            <RouterLink class="button button-secondary" :to="`/chapters/${course.courseId}`"><ListTree :size="16" /> 编辑章节</RouterLink>
            <button
              v-if="course.courseType === 'PRIVATE'"
              class="button button-primary"
              :disabled="Boolean(publishingCourseId)"
              @click="submitPublish(course)"
            >
              <Send :size="16" /> {{ publishingCourseId === course.courseId ? '提交中…' : '提交审核' }}
            </button>
          </footer>
        </article>
      </div>
      <EmptyState v-else title="还没有课程" description="创建第一门课程后，就可以继续添加和编排章节。" />
    </section>

    <ModalDialog :open="createOpen" title="创建课程" description="填写课程的基本信息。" width="wide" @close="createOpen = false">
      <form class="form-layout" @submit.prevent="submitCreate">
        <div class="form-section">
          <label class="field-label" for="course-name">课程名称 <b>*</b></label>
          <input id="course-name" v-model="form.courseName" class="form-input" placeholder="例如：Java 并发编程" autofocus @input="errors.courseName = ''">
          <span v-if="errors.courseName" class="field-error">{{ errors.courseName }}</span>
        </div>

        <div class="form-section">
          <span class="field-label">初始状态</span>
          <div class="choice-grid"><div class="choice-card selected"><span><LockKeyhole :size="20" /></span><div><strong>私有课程</strong><small>创建后仅自己可见，可在内容完成后提交审核。</small></div></div></div>
        </div>

        <div class="form-section">
          <div class="label-row"><span class="field-label">难度等级</span><strong>{{ form.difficultyLevel }} / 5</strong></div>
          <input v-model.number="form.difficultyLevel" class="range-input" type="range" min="1" max="5" step="1">
          <div class="range-labels"><span>入门</span><span>基础</span><span>进阶</span><span>挑战</span><span>专家</span></div>
        </div>

        <div class="form-section">
          <div class="label-row"><label class="field-label" for="course-outline">学习大纲</label><span>{{ form.learningOutline.length }}/2000</span></div>
          <textarea id="course-outline" v-model="form.learningOutline" class="form-textarea" rows="5" placeholder="例如：线程基础、锁机制、线程池、并发容器" @input="errors.learningOutline = ''" />
          <span v-if="errors.learningOutline" class="field-error">{{ errors.learningOutline }}</span>
        </div>

        <footer class="form-actions">
          <button type="button" class="button button-secondary" @click="createOpen = false">取消</button>
          <button class="button button-primary" :disabled="creating">{{ creating ? '创建中…' : '创建课程' }} <ArrowRight v-if="!creating" :size="17" /></button>
        </footer>
      </form>
    </ModalDialog>
  </div>
</template>
