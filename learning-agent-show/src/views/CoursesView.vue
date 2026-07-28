<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, BookOpenText, LockKeyhole, Plus, Send, Sparkles } from 'lucide-vue-next'
import ModalDialog from '@/components/ModalDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { createCourse, publishCourse } from '@/api/courses'
import { ApiError } from '@/api/client'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const { activities, addActivity } = useActivity()
const { showToast } = useToast()

const createOpen = ref(false)
const creating = ref(false)
const publishing = ref(false)
const publishId = ref('')
const publishError = ref('')
const errors = reactive<Record<string, string>>({})
const form = reactive({
  courseName: '',
  difficultyLevel: 3,
  learningOutline: '',
})

const courseActivities = computed(() => activities.value.filter((item) => item.kind.includes('course')))

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
      description: `难度 ${result.difficultyLevel}/5 · 新建课程为私有状态`,
      status: result.courseType,
    })
    publishId.value = String(result.courseId)
    showToast('success', '课程创建成功', `${result.courseName} 的课程 ID 是 ${result.courseId}，已自动填入审核区`)
    createOpen.value = false
    resetForm()
  } catch (error) {
    showToast('error', '创建失败', error instanceof ApiError ? error.message : '发生未知错误')
  } finally {
    creating.value = false
  }
}

async function submitPublish() {
  const id = publishId.value.trim()
  if (!/^[1-9]\d*$/.test(id)) {
    publishError.value = '请输入有效的正整数课程 ID'
    return
  }
  publishError.value = ''
  publishing.value = true
  try {
    const result = await publishCourse(id)
    addActivity({
      kind: 'course-published',
      title: result.courseName || `课程 #${id}`,
      description: `课程 #${id} 已提交审核`,
      status: result.courseType,
    })
    showToast('success', '提交审核成功', `${result.courseName || `课程 #${id}`} 正在等待管理员审核`)
    publishId.value = ''
  } catch (error) {
    showToast('error', '提交审核失败', error instanceof ApiError ? error.message : '发生未知错误')
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <div class="resource-view">
    <section class="page-heading">
      <div>
        <span class="section-kicker">COURSE LIBRARY</span>
        <h2>搭建你的知识地图</h2>
        <p>把学习目标组织成课程，再为每门课程设置合适的难度与大纲。</p>
      </div>
      <button class="button button-primary" @click="createOpen = true"><Plus :size="18" /> 创建课程</button>
    </section>

    <div class="resource-grid">
      <section class="panel resource-list-panel">
        <div class="panel-header">
          <div>
            <span class="section-kicker">LOCAL HISTORY</span>
            <h3>课程操作记录</h3>
          </div>
          <span class="count-pill">{{ courseActivities.length }} 条记录</span>
        </div>
        <div v-if="courseActivities.length" class="record-grid">
          <article v-for="(item, index) in courseActivities" :key="item.id" class="course-record">
            <div class="course-cover" :class="`cover-${index % 4}`">
              <BookOpenText :size="28" />
              <span>{{ item.kind === 'course-published' ? '待审核' : '新课程' }}</span>
            </div>
            <div class="course-record-copy">
              <StatusBadge :status="item.status" />
              <h4>{{ item.title }}</h4>
              <p>{{ item.description }}</p>
              <time>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time>
            </div>
          </article>
        </div>
        <EmptyState
          v-else
          title="课程库还是空的"
          description="点击右上角“创建课程”，完成第一次接口调用。"
        />
      </section>

      <aside class="side-stack">
        <section class="panel publish-panel">
          <span class="side-icon"><Send :size="21" /></span>
          <span class="section-kicker">SUBMIT FOR REVIEW</span>
          <h3>提交课程审核</h3>
          <p>私有课程提交后会进入待审核状态；审核通过后才会向其他学习者公开。</p>
          <form @submit.prevent="submitPublish">
            <label class="field-label" for="publish-course-id">课程 ID</label>
            <div class="inline-field">
              <input id="publish-course-id" v-model="publishId" inputmode="numeric" placeholder="例如：1001" @input="publishError = ''">
              <button class="button button-dark" :disabled="publishing">
                {{ publishing ? '提交中…' : '提交审核' }} <ArrowRight v-if="!publishing" :size="16" />
              </button>
            </div>
            <span v-if="publishError" class="field-error">{{ publishError }}</span>
          </form>
        </section>
        <section class="insight-card">
          <Sparkles :size="20" />
          <div>
            <strong>为什么需要手动输入 ID？</strong>
            <p>创建课程后，响应中的 <code>courseId</code> 会自动填入上方。页面刷新后仍需手动输入，因为后端暂未提供课程列表接口。</p>
          </div>
        </section>
      </aside>
    </div>

    <ModalDialog
      :open="createOpen"
      title="创建一门新课程"
      description="定义清晰的学习边界，是掌握知识的第一步。"
      width="wide"
      @close="createOpen = false"
    >
      <form class="form-layout" @submit.prevent="submitCreate">
        <div class="form-section">
          <label class="field-label" for="course-name">课程名称 <b>*</b></label>
          <input id="course-name" v-model="form.courseName" class="form-input" placeholder="例如：Java 并发编程进阶" autofocus @input="errors.courseName = ''">
          <span v-if="errors.courseName" class="field-error">{{ errors.courseName }}</span>
        </div>

        <div class="form-section">
          <span class="field-label">初始状态</span>
          <div class="choice-grid">
            <div class="choice-card selected">
              <span><LockKeyhole :size="20" /></span>
              <div><strong>私有课程</strong><small>创建后仅自己可学习和管理；提交审核后由管理员决定是否发布。</small></div>
            </div>
          </div>
        </div>

        <div class="form-section">
          <div class="label-row"><span class="field-label">难度等级</span><strong>{{ form.difficultyLevel }} / 5</strong></div>
          <input v-model.number="form.difficultyLevel" class="range-input" type="range" min="1" max="5" step="1">
          <div class="range-labels"><span>入门</span><span>基础</span><span>进阶</span><span>挑战</span><span>专家</span></div>
        </div>

        <div class="form-section">
          <div class="label-row"><label class="field-label" for="course-outline">学习大纲</label><span>{{ form.learningOutline.length }}/2000</span></div>
          <textarea id="course-outline" v-model="form.learningOutline" class="form-textarea" rows="5" placeholder="写下你计划学习的核心主题，例如：线程基础、锁机制、线程池、并发容器……" @input="errors.learningOutline = ''" />
          <span v-if="errors.learningOutline" class="field-error">{{ errors.learningOutline }}</span>
          <span class="field-hint">前端会自动将内容序列化为合法 JSON 字符串，以兼容后端的 JSON 数据库字段。</span>
        </div>

        <footer class="form-actions">
          <button type="button" class="button button-secondary" @click="createOpen = false">取消</button>
          <button class="button button-primary" :disabled="creating">
            {{ creating ? '创建中…' : '创建课程' }} <ArrowRight v-if="!creating" :size="17" />
          </button>
        </footer>
      </form>
    </ModalDialog>
  </div>
</template>
