<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowRight, Bot, CheckCircle2, MessageSquareText, Play, Plus, Target } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { completeSession, createSession } from '@/api/sessions'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const { activities, knownCourses, addActivity } = useActivity()
const { showToast } = useToast()

const createOpen = ref(false)
const creating = ref(false)
const completingId = ref<string | null>(null)
const errors = reactive({ courseId: '', sessionTitle: '' })
const form = reactive({ courseId: '', sessionTitle: '' })

const completedSessionIds = computed(() => new Set(
  activities.value
    .filter((item) => item.kind === 'session-completed' && item.resourceId)
    .map((item) => item.resourceId as string),
))
const sessionActivities = computed(() => activities.value.filter((item) => {
  if (item.kind === 'session-completed') return true
  if (item.kind !== 'session-created') return false
  return !item.resourceId || !completedSessionIds.value.has(item.resourceId)
}))
const selectedCourse = computed(() => knownCourses.value.find((course) => course.courseId === form.courseId) ?? null)

watch(() => route.query.create, (value) => {
  if (value === '1') {
    createOpen.value = true
    router.replace({ query: {} })
  }
}, { immediate: true })

function validate() {
  errors.courseId = selectedCourse.value ? '' : '请选择一门课程'
  errors.sessionTitle = form.sessionTitle.trim() ? '' : '请输入学习目标'
  return !errors.courseId && !errors.sessionTitle
}

async function submitCreate() {
  if (!validate() || !selectedCourse.value) return
  creating.value = true
  try {
    const result = await createSession({ courseId: selectedCourse.value.courseId, sessionTitle: form.sessionTitle.trim() })
    addActivity({
      kind: 'session-created',
      title: result.sessionTitle,
      description: `课程：${selectedCourse.value.courseName}`,
      status: result.sessionStatus,
      resourceId: String(result.id),
    })
    showToast('success', '学习会话已开始', `${result.sessionTitle} 正在进行中`)
    form.courseId = ''
    form.sessionTitle = ''
    createOpen.value = false
  } catch (error) {
    showToast('error', '创建失败', error instanceof ApiError ? error.message : '发生未知错误')
  } finally {
    creating.value = false
  }
}

async function finishSession(item: { id: string; title: string }) {
  if (completingId.value) return
  completingId.value = item.id
  try {
    const result = await completeSession(item.id)
    addActivity({
      kind: 'session-completed',
      title: result.sessionTitle || item.title,
      description: '已完成本次学习目标',
      status: result.sessionStatus,
      resourceId: item.id,
    })
    showToast('success', '学习会话已完成', result.sessionTitle || item.title)
  } catch (error) {
    showToast('error', '完成失败', error instanceof ApiError ? error.message : '学习会话完成失败，请稍后重试')
  } finally {
    completingId.value = null
  }
}
</script>

<template>
  <div class="resource-view">
    <section class="page-heading">
      <div><span class="section-kicker">专注学习</span><h2>学习会话</h2><p>选择课程并记录本次要完成的学习目标。</p></div>
      <button class="button button-primary" :disabled="!knownCourses.length" @click="createOpen = true"><Plus :size="18" /> 开始学习</button>
    </section>

    <section class="panel session-history-panel">
      <div class="panel-header"><div><span class="section-kicker">最近记录</span><h3>学习会话</h3></div><span class="count-pill">{{ sessionActivities.length }} 条</span></div>
      <div v-if="sessionActivities.length" class="session-timeline">
        <article v-for="item in sessionActivities" :key="item.id" class="session-record">
          <span class="timeline-mark" :class="item.status === 'COMPLETED' ? 'done' : 'active'">
            <CheckCircle2 v-if="item.status === 'COMPLETED'" :size="18" /><Play v-else :size="16" />
          </span>
          <div class="session-record-copy">
            <div><h4>{{ item.title }}</h4><StatusBadge :status="item.status" /></div>
            <p>{{ item.description }}</p>
            <time>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time>
            <div v-if="item.kind === 'session-created' && item.resourceId" class="session-record-actions">
              <RouterLink class="button button-primary session-chat-button" :to="{ name: 'agent-chat', query: { session: item.resourceId } }"><Bot :size="14" /> 与 AI 学习</RouterLink>
              <button class="button button-secondary session-complete-button" :disabled="completingId === item.resourceId" @click="finishSession({ id: item.resourceId, title: item.title })">
                {{ completingId === item.resourceId ? '完成中…' : '完成会话' }}
              </button>
            </div>
          </div>
        </article>
      </div>
      <div v-else-if="!knownCourses.length" class="session-empty-action">
        <EmptyState title="还没有课程" description="创建课程后才能开始学习会话。" />
        <RouterLink class="button button-primary" to="/courses?create=1"><Plus :size="16" /> 创建课程</RouterLink>
      </div>
      <EmptyState v-else title="还没有学习会话" description="从右上角开始一次专注学习。" />
    </section>

    <ModalDialog :open="createOpen" title="开始学习" description="选择课程并填写本次学习目标。" @close="createOpen = false">
      <form class="form-layout" @submit.prevent="submitCreate">
        <div class="session-form-illustration"><span><Target :size="28" /></span><div><strong>本次学习目标</strong><p>目标尽量具体，并能在一次学习中完成。</p></div></div>
        <div class="form-section">
          <label class="field-label" for="session-course">选择课程 <b>*</b></label>
          <select id="session-course" v-model="form.courseId" class="form-select" autofocus @change="errors.courseId = ''">
            <option value="" disabled>请选择课程</option>
            <option v-for="course in knownCourses" :key="course.courseId" :value="course.courseId">{{ course.courseName }}</option>
          </select>
          <span v-if="errors.courseId" class="field-error">{{ errors.courseId }}</span>
        </div>
        <div class="form-section">
          <label class="field-label" for="session-title">学习目标 <b>*</b></label>
          <div class="input-with-icon"><MessageSquareText :size="18" /><input id="session-title" v-model="form.sessionTitle" class="form-input" placeholder="例如：理解 synchronized 的锁升级过程" @input="errors.sessionTitle = ''"></div>
          <span v-if="errors.sessionTitle" class="field-error">{{ errors.sessionTitle }}</span>
        </div>
        <footer class="form-actions">
          <button type="button" class="button button-secondary" @click="createOpen = false">取消</button>
          <button class="button button-primary" :disabled="creating">{{ creating ? '创建中…' : '开始学习' }} <ArrowRight v-if="!creating" :size="17" /></button>
        </footer>
      </form>
    </ModalDialog>
  </div>
</template>
