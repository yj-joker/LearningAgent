<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Save, Trash2 } from 'lucide-vue-next'
import { ApiError } from '@/api/client'
import { activateLearningPlanDraft, createLearningPlanDraft, listLearningPlanDrafts, updateLearningPlanDraft } from '@/api/learningPlans'
import EmptyState from '@/components/EmptyState.vue'
import { useToast } from '@/composables/useToast'
import type {
  LearningPlanDraft,
  LearningPlanDraftCreatePayload,
  LearningPlanDraftStepPayload,
  LearningPlanDraftUpdatePayload,
} from '@/types/api'

interface DraftFormStep extends LearningPlanDraftStepPayload {
  stepRef?: string | null
}

const { showToast } = useToast()
const drafts = ref<LearningPlanDraft[]>([])
const selectedRef = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const formError = ref('')
const form = reactive({
  title: '',
  objective: '',
  learnerProfile: '',
  weeklyCommitment: '',
  constraints: '',
  expectedVersion: 1,
  steps: [] as DraftFormStep[],
})

const isEditing = computed(() => Boolean(selectedRef.value))
const selectedDraft = computed(() => drafts.value.find((draft) => draft.draftRef === selectedRef.value) ?? null)
const isActive = computed(() => selectedDraft.value?.status === 'ACTIVE')

// 从后端加载草案列表，重启后也能从数据库恢复页面状态。
async function loadDrafts(selectLatest = false) {
  loading.value = true
  errorMessage.value = ''
  try {
    drafts.value = await listLearningPlanDrafts()
    if (selectLatest && drafts.value.length) openDraft(drafts.value[0]!)
    else if (selectedRef.value) {
      const current = drafts.value.find((draft) => draft.draftRef === selectedRef.value)
      if (current) openDraft(current)
      else resetForm()
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '草案加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

// 把后端草案复制到编辑表单；编辑过程不会立即改数据库。
function openDraft(draft: LearningPlanDraft) {
  selectedRef.value = draft.draftRef
  form.title = draft.title
  form.objective = draft.objective
  form.learnerProfile = draft.learnerProfile ?? ''
  form.weeklyCommitment = draft.weeklyCommitment ?? ''
  form.constraints = draft.constraints ?? ''
  form.expectedVersion = draft.version
  form.steps = draft.steps.map((step) => ({
    stepRef: step.stepRef,
    description: step.description,
    completionCriteria: step.completionCriteria,
  }))
  formError.value = ''
}

// 清空表单，开始一份新的未生效草案。
function resetForm() {
  selectedRef.value = null
  form.title = ''
  form.objective = ''
  form.learnerProfile = ''
  form.weeklyCommitment = ''
  form.constraints = ''
  form.expectedVersion = 1
  form.steps = [{ description: '', completionCriteria: '' }]
  formError.value = ''
}

// 添加一个空步骤，步骤顺序由后端按数组顺序保存。
function addStep() {
  if (form.steps.length >= 12) return
  form.steps.push({ description: '', completionCriteria: '' })
}

// 删除步骤；至少保留一个步骤，避免保存空草案。
function removeStep(index: number) {
  if (form.steps.length <= 1) return
  form.steps.splice(index, 1)
}

// 检查页面输入，后端仍会做相同的长度和归属校验。
function validate() {
  formError.value = ''
  if (!form.title.trim()) formError.value = '请填写草案标题'
  else if (!form.objective.trim()) formError.value = '请填写总体学习目标'
  else if (!form.steps.length || form.steps.some((step) => !step.description.trim() || !step.completionCriteria.trim())) {
    formError.value = '每个步骤都需要填写内容和完成条件'
  }
  return !formError.value
}

// 手动创建或更新草案；版本冲突时提示重新读取，避免覆盖 Agent 的修改。
async function saveDraft() {
  if (!validate() || saving.value) return
  saving.value = true
  const steps = form.steps.map((step) => ({
    ...(step.stepRef ? { stepRef: step.stepRef } : {}),
    description: step.description.trim(),
    completionCriteria: step.completionCriteria.trim(),
  }))
  try {
    let result: LearningPlanDraft
    if (selectedRef.value) {
      const payload: LearningPlanDraftUpdatePayload = {
        draftRef: selectedRef.value,
        expectedVersion: form.expectedVersion,
        title: form.title.trim(),
        objective: form.objective.trim(),
        learnerProfile: form.learnerProfile.trim() || null,
        weeklyCommitment: form.weeklyCommitment.trim() || null,
        constraints: form.constraints.trim() || null,
        steps,
      }
      result = await updateLearningPlanDraft(selectedRef.value, payload)
    } else {
      const payload: LearningPlanDraftCreatePayload = {
        title: form.title.trim(),
        objective: form.objective.trim(),
        learnerProfile: form.learnerProfile.trim() || null,
        weeklyCommitment: form.weeklyCommitment.trim() || null,
        constraints: form.constraints.trim() || null,
        steps,
      }
      result = await createLearningPlanDraft(payload)
    }
    showToast('success', '学习计划已保存', result.status === 'ACTIVE' ? '正式计划已更新，版本已递增' : '当前状态仍为未正式生效')
    drafts.value = [result, ...drafts.value.filter((draft) => draft.draftRef !== result.draftRef)]
    openDraft(result)
  } catch (error) {
    showToast('error', '草案保存失败', error instanceof ApiError ? error.message : '请刷新后重试')
    if (error instanceof ApiError && error.status === 409) await loadDrafts()
  } finally {
    saving.value = false
  }
}

// 用户明确点击确认后正式生效；版本冲突时重新读取，不能覆盖新的编辑。
async function activateDraft() {
  if (!selectedRef.value || !selectedDraft.value || isActive.value || saving.value) return
  saving.value = true
  try {
    const result = await activateLearningPlanDraft(selectedRef.value, selectedDraft.value.version)
    showToast('success', '学习计划已正式生效', '后续专注模式可以选择这份计划')
    drafts.value = [result, ...drafts.value.filter((draft) => draft.draftRef !== result.draftRef)]
    openDraft(result)
  } catch (error) {
    showToast('error', '学习计划确认失败', error instanceof ApiError ? error.message : '请刷新后重试')
    if (error instanceof ApiError && error.status === 409) await loadDrafts()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  resetForm()
  void loadDrafts()
})
</script>

<template>
  <div class="resource-view learning-plan-drafts-view">
    <section class="page-heading">
      <div>
        <span class="section-kicker">长期学习规划</span>
        <h2>学习计划草案</h2>
        <p>手动编辑或让 AI 助教继续讨论同一份草案；确认后可正式生效。</p>
      </div>
      <button class="button button-primary" type="button" @click="resetForm"><Plus :size="17" /> 新建草案</button>
    </section>

    <p v-if="errorMessage" class="learning-plan-error">{{ errorMessage }}</p>

    <div class="learning-plan-draft-layout">
      <section class="panel learning-plan-draft-list">
        <div class="panel-header">
          <div><span class="section-kicker">已保存内容</span><h3>我的学习计划</h3></div>
          <span class="count-pill">{{ drafts.length }} 份</span>
        </div>
        <div v-if="loading" class="learning-plan-loading">正在读取草案…</div>
        <div v-else-if="drafts.length" class="learning-plan-items">
          <button
            v-for="draft in drafts"
            :key="draft.draftRef"
            type="button"
            class="learning-plan-item"
            :class="{ active: draft.draftRef === selectedRef }"
            @click="openDraft(draft)"
          >
            <strong>{{ draft.title }}</strong>
            <span>{{ draft.steps.length }} 个步骤 · v{{ draft.version }}</span>
            <small><span class="draft-status-dot">{{ draft.status }}</span> {{ draft.source === 'AGENT' ? 'Agent 创建' : '手动创建' }}</small>
          </button>
        </div>
        <EmptyState v-else title="还没有学习计划草案" description="先在右侧整理一个目标，或让 AI 助教和你一起讨论。" />
      </section>

      <section class="panel learning-plan-editor">
        <div class="panel-header">
          <div><span class="section-kicker">学习计划编辑器</span><h3>{{ isActive ? '正式学习计划' : (isEditing ? '编辑当前草案' : '创建新草案') }}</h3></div>
          <span class="draft-formal-badge">{{ isActive ? 'ACTIVE · 已正式生效' : 'DRAFT · 未正式生效' }}</span>
        </div>
        <form class="form-layout" @submit.prevent="saveDraft">
          <div class="form-section">
            <label class="field-label" for="draft-title">标题 <b>*</b></label>
            <input id="draft-title" v-model="form.title" class="form-input" maxlength="200" placeholder="例如：Java 并发编程学习计划" />
          </div>
          <div class="form-section">
            <label class="field-label" for="draft-objective">总体目标 <b>*</b></label>
            <textarea id="draft-objective" v-model="form.objective" class="form-textarea" maxlength="2000" rows="4" placeholder="希望最终具备什么能力？" />
          </div>
          <div class="learning-plan-grid-fields">
            <div class="form-section"><label class="field-label" for="draft-profile">当前基础</label><textarea id="draft-profile" v-model="form.learnerProfile" class="form-textarea" maxlength="1000" rows="3" /></div>
            <div class="form-section"><label class="field-label" for="draft-time">每周投入</label><textarea id="draft-time" v-model="form.weeklyCommitment" class="form-textarea" maxlength="500" rows="3" /></div>
          </div>
          <div class="form-section"><label class="field-label" for="draft-constraints">限制条件</label><textarea id="draft-constraints" v-model="form.constraints" class="form-textarea" maxlength="2000" rows="3" placeholder="例如：工作日每天 30 分钟" /></div>

          <div class="form-section">
            <div class="label-row"><span class="field-label">阶段步骤 <b>*</b></span><button class="button button-secondary" type="button" :disabled="form.steps.length >= 12" @click="addStep"><Plus :size="15" /> 添加步骤</button></div>
            <div v-for="(step, index) in form.steps" :key="step.stepRef ?? `new-${index}`" class="learning-plan-step-editor">
              <div class="learning-plan-step-title"><strong>步骤 {{ index + 1 }}</strong><button type="button" aria-label="删除步骤" :disabled="form.steps.length <= 1" @click="removeStep(index)"><Trash2 :size="15" /></button></div>
              <input v-model="step.description" class="form-input" maxlength="1000" placeholder="这个阶段要学习什么？" />
              <input v-model="step.completionCriteria" class="form-input" maxlength="1000" placeholder="达到什么程度算完成？" />
            </div>
          </div>
          <p v-if="formError" class="field-error">{{ formError }}</p>
          <footer class="form-actions">
            <span v-if="isEditing" class="learning-plan-version">当前版本 v{{ form.expectedVersion }}</span>
            <button v-if="selectedRef && !isActive" class="button button-secondary" type="button" :disabled="saving" @click="activateDraft">确认正式生效</button>
            <button class="button button-primary" type="submit" :disabled="saving"><Save :size="16" /> {{ saving ? '保存中…' : (isActive ? '保存正式计划' : '保存草案') }}</button>
          </footer>
        </form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.learning-plan-draft-layout { display: grid; grid-template-columns: minmax(230px, .8fr) minmax(0, 1.6fr); gap: 20px; align-items: start; }
.learning-plan-draft-list, .learning-plan-editor { min-width: 0; }
.learning-plan-items { display: grid; gap: 8px; }
.learning-plan-item { display: grid; gap: 5px; padding: 13px; text-align: left; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; cursor: pointer; }
.learning-plan-item.active { border-color: #319897; box-shadow: 0 0 0 2px rgba(49, 152, 151, .12); }
.learning-plan-item span, .learning-plan-item small, .learning-plan-version { color: #64748b; font-size: 12px; }
.learning-plan-item small { display: flex; align-items: center; gap: 6px; }
.learning-plan-loading { color: #64748b; padding: 18px 0; }
.learning-plan-error { color: #b42318; margin: 0 0 16px; }
.draft-formal-badge { color: #9a6700; background: #fff4cc; border-radius: 999px; padding: 5px 10px; font-size: 11px; font-weight: 700; }
.learning-plan-grid-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.learning-plan-step-editor { display: grid; gap: 8px; padding: 13px; margin-top: 10px; border: 1px solid #e2e8f0; border-radius: 10px; }
.learning-plan-step-title { display: flex; justify-content: space-between; align-items: center; }
.learning-plan-step-title button { border: 0; background: transparent; color: #b42318; cursor: pointer; }
.learning-plan-step-title button:disabled { color: #cbd5e1; cursor: not-allowed; }
.form-actions { display: flex; justify-content: flex-end; align-items: center; gap: 12px; }
@media (max-width: 860px) { .learning-plan-draft-layout, .learning-plan-grid-fields { grid-template-columns: 1fr; } }
</style>
