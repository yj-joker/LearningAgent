<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, CheckCircle2, MessageSquareText, Play, Plus, Sparkles, Target } from 'lucide-vue-next'
import ModalDialog from '@/components/ModalDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { completeSession, createSession } from '@/api/sessions'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const { activities, addActivity } = useActivity()
const { showToast } = useToast()

const createOpen = ref(false)
const creating = ref(false)
const completing = ref(false)
const completeId = ref('')
const completeError = ref('')
const errors = reactive({ courseId: '', sessionTitle: '' })
const form = reactive({ courseId: '', sessionTitle: '' })

const sessionActivities = computed(() => activities.value.filter((item) => item.kind.includes('session')))

watch(() => route.query.create, (value) => {
  if (value === '1') {
    createOpen.value = true
    router.replace({ query: {} })
  }
}, { immediate: true })

function validate() {
  const courseId = Number(form.courseId)
  errors.courseId = Number.isInteger(courseId) && courseId > 0 ? '' : '请输入有效的正整数课程 ID'
  errors.sessionTitle = form.sessionTitle.trim() ? '' : '请输入学习会话标题'
  return !errors.courseId && !errors.sessionTitle
}

async function submitCreate() {
  if (!validate()) return
  creating.value = true
  try {
    const result = await createSession({
      courseId: Number(form.courseId),
      sessionTitle: form.sessionTitle.trim(),
    })
    addActivity({
      kind: 'session-created',
      title: result.sessionTitle,
      description: `基于课程 #${form.courseId} 创建学习会话`,
      status: result.sessionStatus,
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

async function submitComplete() {
  const id = Number(completeId.value)
  if (!Number.isInteger(id) || id <= 0) {
    completeError.value = '请输入有效的正整数会话 ID'
    return
  }
  completeError.value = ''
  completing.value = true
  try {
    const result = await completeSession(id)
    addActivity({
      kind: 'session-completed',
      title: result.sessionTitle || `学习会话 #${id}`,
      description: `学习会话 #${id} 已标记完成`,
      status: result.sessionStatus || 'COMPLETED',
    })
    showToast('success', '太棒了，已完成！', `${result.sessionTitle || `会话 #${id}`} 已完成`)
    completeId.value = ''
  } catch (error) {
    showToast('error', '操作失败', error instanceof ApiError ? error.message : '发生未知错误')
  } finally {
    completing.value = false
  }
}
</script>

<template>
  <div class="resource-view">
    <section class="page-heading">
      <div>
        <span class="section-kicker">FOCUS SESSIONS</span>
        <h2>一次只攻克一个目标</h2>
        <p>为课程开启一次专注学习会话，完成后留下清晰的进度足迹。</p>
      </div>
      <button class="button button-primary" @click="createOpen = true"><Plus :size="18" /> 开始学习</button>
    </section>

    <div class="resource-grid">
      <section class="panel resource-list-panel">
        <div class="panel-header">
          <div>
            <span class="section-kicker">SESSION HISTORY</span>
            <h3>会话操作记录</h3>
          </div>
          <span class="count-pill">{{ sessionActivities.length }} 条记录</span>
        </div>
        <div v-if="sessionActivities.length" class="session-timeline">
          <article v-for="item in sessionActivities" :key="item.id" class="session-record">
            <span class="timeline-mark" :class="item.status === 'COMPLETED' ? 'done' : 'active'">
              <CheckCircle2 v-if="item.status === 'COMPLETED'" :size="18" />
              <Play v-else :size="16" />
            </span>
            <div class="session-record-copy">
              <div><h4>{{ item.title }}</h4><StatusBadge :status="item.status" /></div>
              <p>{{ item.description }}</p>
              <time>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time>
            </div>
          </article>
        </div>
        <EmptyState
          v-else
          title="还没有学习会话"
          description="选择一个已有课程 ID，开始第一次专注学习。"
        />
      </section>

      <aside class="side-stack">
        <section class="panel publish-panel complete-panel">
          <span class="side-icon"><CheckCircle2 :size="21" /></span>
          <span class="section-kicker">COMPLETE</span>
          <h3>完成学习会话</h3>
          <p>仅状态为 ACTIVE 且属于当前用户的会话可以被标记为完成。</p>
          <form @submit.prevent="submitComplete">
            <label class="field-label" for="complete-session-id">学习会话 ID</label>
            <div class="inline-field">
              <input id="complete-session-id" v-model="completeId" inputmode="numeric" placeholder="例如：2001" @input="completeError = ''">
              <button class="button button-dark" :disabled="completing">
                {{ completing ? '提交中…' : '标记完成' }} <ArrowRight v-if="!completing" :size="16" />
              </button>
            </div>
            <span v-if="completeError" class="field-error">{{ completeError }}</span>
          </form>
        </section>
        <section class="insight-card insight-purple">
          <Sparkles :size="20" />
          <div>
            <strong>当前接口能力说明</strong>
            <p>创建会话的响应只包含标题和状态，没有返回会话 <code>id</code>。因此完成操作同样需要手动填写数据库 ID。</p>
          </div>
        </section>
      </aside>
    </div>

    <ModalDialog
      :open="createOpen"
      title="开启学习会话"
      description="明确这次要完成什么，让注意力只停留在当前目标。"
      @close="createOpen = false"
    >
      <form class="form-layout" @submit.prevent="submitCreate">
        <div class="session-form-illustration">
          <span><Target :size="30" /></span>
          <div><strong>设定本次学习目标</strong><p>一个具体标题，比“随便学学”更容易完成。</p></div>
        </div>
        <div class="form-section">
          <label class="field-label" for="session-course-id">所属课程 ID <b>*</b></label>
          <input id="session-course-id" v-model="form.courseId" class="form-input" inputmode="numeric" placeholder="例如：1001" autofocus @input="errors.courseId = ''">
          <span v-if="errors.courseId" class="field-error">{{ errors.courseId }}</span>
          <span class="field-hint">课程必须存在，且为当前用户的课程或公开课程。</span>
        </div>
        <div class="form-section">
          <label class="field-label" for="session-title">会话标题 <b>*</b></label>
          <div class="input-with-icon">
            <MessageSquareText :size="18" />
            <input id="session-title" v-model="form.sessionTitle" class="form-input" placeholder="例如：理解 synchronized 的锁升级过程" @input="errors.sessionTitle = ''">
          </div>
          <span v-if="errors.sessionTitle" class="field-error">{{ errors.sessionTitle }}</span>
        </div>
        <div class="form-note"><Play :size="16" /> 创建后，后端会自动将会话状态设为 <code>ACTIVE</code>。</div>
        <footer class="form-actions">
          <button type="button" class="button button-secondary" @click="createOpen = false">取消</button>
          <button class="button button-primary" :disabled="creating">
            {{ creating ? '创建中…' : '开始学习' }} <ArrowRight v-if="!creating" :size="17" />
          </button>
        </footer>
      </form>
    </ModalDialog>
  </div>
</template>
