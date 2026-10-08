<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ArrowDown, ArrowUp, Check, Plus, RefreshCw, Save, Search, Trash2 } from 'lucide-vue-next'
import { ApiError } from '@/api/client'
import { activateLearningPlanDraft, createLearningPlanDraft, listLearningPlanDrafts, updateLearningPlanDraft } from '@/api/learningPlans'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
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
const search = ref('')
const statusFilter = ref('ALL')
const savedSnapshot = ref('')
const pendingSelection = ref<LearningPlanDraft | 'NEW' | null>(null)
const leaveOpen = ref(false)
let resolvePendingLeave: ((leave: boolean) => void) | null = null
const activationOpen = ref(false)
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
const isArchived = computed(() => selectedDraft.value?.status === 'ARCHIVED')
const filteredDrafts = computed(() => drafts.value.filter(item =>
  (statusFilter.value === 'ALL' || item.status === statusFilter.value)
  && `${item.title} ${item.objective}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const dirty = computed(() => savedSnapshot.value !== JSON.stringify(form))

// 切换前保留编辑机会，防止选中另一份计划时静默丢失输入。
function requestSelection(target: LearningPlanDraft | 'NEW') {
  if (saving.value || leaveOpen.value || pendingSelection.value) return
  if (target !== 'NEW' && target.draftRef === selectedRef.value) return
  if (dirty.value) pendingSelection.value = target
  else if (target === 'NEW') resetForm()
  else openDraft(target)
}
function discardAndSwitch() {
  if (saving.value || leaveOpen.value) return
  const target = pendingSelection.value
  pendingSelection.value = null
  if (target === 'NEW') resetForm()
  else if (target) openDraft(target)
}

// 路由确认与切换草案分开保存，取消只终止这次导航，不会误切另一份草案。
function finishLeave(leave: boolean) {
  const resolve = resolvePendingLeave
  resolvePendingLeave = null
  leaveOpen.value = false
  resolve?.(leave)
}

// 浏览器刷新或关闭只能使用浏览器原生提醒，保存请求未结束时也保留离开提醒。
function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => {
  if (saving.value) {
    showToast('info', '正在保存学习计划', '保存完成后再离开页面')
    return false
  }
  // 已有确认待处理时拒绝新的导航，避免多个导航共用一个弹窗决定。
  if (pendingSelection.value || resolvePendingLeave) return false
  if (!dirty.value) return true
  activationOpen.value = false
  leaveOpen.value = true
  return new Promise<boolean>((resolve) => { resolvePendingLeave = resolve })
})
function moveStep(index: number, direction: number) {
  const next = index + direction
  if (next < 0 || next >= form.steps.length) return
  const [step] = form.steps.splice(index, 1)
  if (step) form.steps.splice(next, 0, step)
}

// 从后端加载草案列表，重启后也能从数据库恢复页面状态。
async function loadDrafts(selectLatest = false) {
  loading.value = true
  errorMessage.value = ''
  try {
    drafts.value = await listLearningPlanDrafts()
    if (selectLatest && drafts.value.length) openDraft(drafts.value[0]!)
    else if (selectedRef.value) {
      const current = drafts.value.find((draft) => draft.draftRef === selectedRef.value)
      if (current && !dirty.value) openDraft(current)
      else if (!current && !dirty.value) resetForm()
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
  savedSnapshot.value = JSON.stringify(form)
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
  savedSnapshot.value = JSON.stringify(form)
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
  if (saving.value || isArchived.value || leaveOpen.value || pendingSelection.value || !validate()) return
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
  if (!selectedRef.value || !selectedDraft.value || isActive.value || saving.value || dirty.value || isArchived.value) return
  activationOpen.value = false
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

// 在首次渲染前建立空表单基线，避免初始加载期间误报未保存修改。
resetForm()

onMounted(() => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  void loadDrafts()
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', warnBeforeUnload)
  // 意外卸载也终止尚未决定的导航，不把悬空确认留到下一个页面。
  resolvePendingLeave?.(false)
  resolvePendingLeave = null
})
</script>

<template>
  <div class="resource-view learning-plan-drafts-view">
    <section class="page-heading">
      <div>
        <span class="section-kicker">长期学习规划</span>
        <h2>学习计划草案</h2>
      </div>
      <button class="button button-primary" type="button" :disabled="saving" @click="requestSelection('NEW')"><Plus :size="17" /> 新建草案</button>
    </section>

    <p v-if="errorMessage" class="learning-plan-error">{{ errorMessage }}</p>

    <div class="learning-plan-draft-layout">
      <section class="panel learning-plan-draft-list">
        <div class="panel-header">
          <div><span class="section-kicker">已保存内容</span><h3>我的学习计划</h3></div>
          <div class="list-header-actions"><span class="count-pill">{{ drafts.length }} 份</span><button class="icon-button" type="button" title="刷新计划列表" aria-label="刷新计划列表" :disabled="loading || saving" @click="loadDrafts()"><RefreshCw :size="15" /></button></div>
        </div>
        <div class="draft-list-tools"><label class="draft-search"><Search :size="16" /><input v-model="search" aria-label="搜索学习计划" placeholder="搜索标题或学习目标" /></label><select v-model="statusFilter" class="form-select" aria-label="筛选计划状态"><option value="ALL">全部状态</option><option value="DRAFT">未生效草案</option><option value="ACTIVE">已生效计划</option><option value="ARCHIVED">已归档</option></select></div>
        <div v-if="loading" class="learning-plan-loading">正在读取草案…</div>
        <div v-else-if="filteredDrafts.length" class="learning-plan-items">
          <button
            v-for="draft in filteredDrafts"
            :key="draft.draftRef"
            type="button"
            class="learning-plan-item"
            :class="{ active: draft.draftRef === selectedRef }"
            :disabled="saving"
            @click="requestSelection(draft)"
          >
            <strong>{{ draft.title }}</strong>
            <p>{{ draft.objective }}</p>
            <span>{{ draft.steps.length }} 个步骤 · v{{ draft.version }}</span>
            <small><span class="draft-status-dot" :class="draft.status.toLowerCase()">{{ { DRAFT: '草案', ACTIVE: '已生效', ARCHIVED: '已归档' }[draft.status] }}</span> {{ draft.source === 'AGENT' ? 'AI 助教创建' : '手动创建' }}</small>
          </button>
        </div>
        <EmptyState v-else :title="drafts.length ? '没有匹配的计划' : '还没有学习计划'" :description="drafts.length ? '暂无符合当前筛选条件的内容' : '暂无已保存的草案'" />
      </section>

      <section class="panel learning-plan-editor">
        <div class="panel-header">
          <div><span class="section-kicker">学习计划编辑器</span><h3>{{ isActive ? '正式学习计划' : (isEditing ? '编辑当前草案' : '创建新草案') }}</h3></div>
          <span class="draft-formal-badge" :class="{ 'is-active': isActive }">{{ isArchived ? '已归档' : isActive ? '已正式生效' : '未正式生效' }}</span>
        </div>
        <form id="learning-plan-draft-form" class="form-layout" @submit.prevent="saveDraft">
          <fieldset :disabled="saving || isArchived" class="draft-fields">
          <h4 class="editor-section-title">目标与安排</h4>
          <div class="form-section">
            <label class="field-label" for="draft-title">标题 <b>*</b></label>
            <input id="draft-title" v-model="form.title" class="form-input" maxlength="200" placeholder="例如：Java 并发编程学习计划" />
          </div>
          <div class="form-section">
            <label class="field-label" for="draft-objective">总体目标 <b>*</b></label>
            <textarea id="draft-objective" v-model="form.objective" class="form-textarea" maxlength="2000" rows="3" placeholder="希望最终具备什么能力？" />
          </div>
          <div class="learning-plan-grid-fields">
            <div class="form-section"><label class="field-label" for="draft-profile">当前基础</label><textarea id="draft-profile" v-model="form.learnerProfile" class="form-textarea" maxlength="1000" rows="3" /></div>
            <div class="form-section"><label class="field-label" for="draft-time">每周投入</label><textarea id="draft-time" v-model="form.weeklyCommitment" class="form-textarea" maxlength="500" rows="2" /></div>
          </div>
          <div class="form-section"><label class="field-label" for="draft-constraints">限制条件</label><textarea id="draft-constraints" v-model="form.constraints" class="form-textarea" maxlength="2000" rows="2" placeholder="例如：工作日每天 30 分钟" /></div>

          <div class="form-section">
            <div class="label-row"><span class="field-label">阶段步骤 <b>*</b></span><button class="button button-secondary" type="button" :disabled="form.steps.length >= 12" @click="addStep"><Plus :size="15" /> 添加步骤</button></div>
            <div v-for="(step, index) in form.steps" :key="step.stepRef ?? `new-${index}`" class="learning-plan-step-editor">
              <div class="learning-plan-step-title"><strong>步骤 {{ index + 1 }}</strong><div><button class="icon-button" type="button" title="上移步骤" aria-label="上移步骤" :disabled="index === 0" @click="moveStep(index, -1)"><ArrowUp :size="15" /></button><button class="icon-button" type="button" title="下移步骤" aria-label="下移步骤" :disabled="index === form.steps.length - 1" @click="moveStep(index, 1)"><ArrowDown :size="15" /></button><button class="icon-button" type="button" title="删除步骤" aria-label="删除步骤" :disabled="form.steps.length <= 1" @click="removeStep(index)"><Trash2 :size="15" /></button></div></div>
              <label :for="`step-content-${index}`" class="field-label">学习内容</label><textarea :id="`step-content-${index}`" v-model="step.description" class="form-textarea" maxlength="1000" rows="2" />
              <label :for="`step-criteria-${index}`" class="field-label">完成条件</label><textarea :id="`step-criteria-${index}`" v-model="step.completionCriteria" class="form-textarea" maxlength="1000" rows="2" />
            </div>
          </div>
          </fieldset>
          <p v-if="formError" class="field-error">{{ formError }}</p>
        </form>
      </section>
    </div>
    <Teleport to="body">
      <footer class="draft-save-bar" aria-label="学习计划保存操作">
        <span class="learning-plan-version" role="status">{{ isArchived ? '已归档 · 只读' : dirty ? '有未保存的修改' : isEditing ? `已保存 · v${form.expectedVersion}` : '新草案' }}</span>
        <button v-if="selectedRef && !isActive && !isArchived" class="button button-secondary" type="button" :disabled="saving || dirty || leaveOpen || Boolean(pendingSelection)" :title="dirty ? '请先保存修改' : '确认正式生效'" @click="activationOpen = true"><Check :size="16" /> 确认生效</button>
        <button v-if="!isArchived" class="button button-primary" type="submit" form="learning-plan-draft-form" :disabled="saving || !dirty || leaveOpen || Boolean(pendingSelection)"><Save :size="16" /> {{ saving ? '保存中…' : (isActive ? '保存计划' : '保存草案') }}</button>
      </footer>
    </Teleport>
    <ModalDialog :open="Boolean(pendingSelection)" title="有未保存的修改" description="离开当前计划会丢失本次编辑。" @close="pendingSelection = null"><footer class="form-actions"><button class="button button-secondary" @click="pendingSelection = null">继续编辑</button><button class="button button-primary" @click="discardAndSwitch">放弃修改并切换</button></footer></ModalDialog>
    <ModalDialog :open="leaveOpen" title="有未保存的修改" description="离开页面会丢失本次编辑，请确认是否放弃。" @close="finishLeave(false)"><footer class="form-actions"><button class="button button-secondary" type="button" @click="finishLeave(false)">继续编辑</button><button class="button button-primary" type="button" @click="finishLeave(true)">放弃修改并离开</button></footer></ModalDialog>
    <ModalDialog :open="activationOpen" title="确认学习计划生效" :description="selectedDraft?.title ?? ''" @close="activationOpen = false"><p>确认后，这份计划可以关联到学习会话。</p><footer class="form-actions"><button class="button button-secondary" @click="activationOpen = false">取消</button><button class="button button-primary" @click="activateDraft">确认生效</button></footer></ModalDialog>
  </div>
</template>

<style scoped>
.learning-plan-drafts-view { padding-bottom: calc(100px + env(safe-area-inset-bottom, 0px)); }
.draft-save-bar { position: fixed; z-index: 35; left: 244px; right: 0; bottom: 0; display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 12px; padding: 14px 32px calc(14px + env(safe-area-inset-bottom, 0px)); border-top: 1px solid #cee0df; background: rgba(255, 255, 255, .98); box-shadow: 0 -8px 24px rgba(27, 88, 105, .08); }
.draft-save-bar:global(body:has(.app-shell.sidebar-collapsed) .draft-save-bar) { left: 84px; }
.draft-save-bar .button { flex-shrink: 0; }
.learning-plan-draft-layout { display: grid; grid-template-columns: minmax(240px, 320px) minmax(0, 1fr); gap: 24px; align-items: start; }
.learning-plan-drafts-view :deep(.panel) { border-radius: 0; border: 0; box-shadow: none; background: transparent; }
.learning-plan-draft-list { padding-right: 20px; border-right: 1px solid #cee0df !important; }
.learning-plan-editor { background: #fff !important; padding: 24px; }
.draft-fields { display: grid; gap: 18px; padding: 0; margin: 0; min-width: 0; border: 0; }
.draft-fields .form-textarea { min-height: 76px; font-size: 13px; line-height: 1.7; }
.draft-fields #draft-objective { min-height: 104px; }
.editor-section-title { margin: 0; font-size: 13px; color: #526b66; }
.list-header-actions { display: flex; align-items: center; gap: 4px; }
.draft-list-tools { display: grid; gap: 10px; margin-bottom: 16px; }
.draft-search { display: flex; align-items: center; gap: 8px; background: white; border: 1px solid #dce6e3; padding: 10px; border-radius: 6px; color: #7a8885; }
.draft-search input { width: 100%; min-width: 0; background: transparent; border: 0; outline: 0; font-size: 12px; }
.learning-plan-draft-list, .learning-plan-editor { min-width: 0; }
.learning-plan-items { display: grid; gap: 8px; max-height: 680px; overflow: auto; padding: 3px; }
.learning-plan-item { display: grid; gap: 8px; padding: 14px; text-align: left; border: 1px solid #e2e8e6; border-radius: 6px; background: #fff; cursor: pointer; min-width: 0; overflow-wrap: anywhere; }
.learning-plan-item strong { font-size: 13px; line-height: 1.6; }
.learning-plan-item p { margin: 0; color: #7a8885; font-size: 11px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.draft-status-dot { padding: 2px 6px; border-radius: 4px; background: #fff3cf; color: #946c20 !important; }
.draft-status-dot.active, .draft-formal-badge.is-active { color: #287774 !important; background: #e5f4ef; }
.draft-status-dot.archived { color: #7a8885 !important; background: #eef1f0; }
.learning-plan-item.active { border-color: #319897; box-shadow: 0 0 0 2px rgba(49, 152, 151, .12); }
.learning-plan-item span, .learning-plan-item small, .learning-plan-version { color: #64748b; font-size: 12px; }
.learning-plan-item small { display: flex; align-items: center; gap: 6px; }
.learning-plan-loading { color: #64748b; padding: 18px 0; }
.learning-plan-error { color: #b42318; margin: 0 0 16px; }
.draft-formal-badge { color: #9a6700; background: #fff4cc; border-radius: 999px; padding: 5px 10px; font-size: 11px; font-weight: 700; }
.learning-plan-grid-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.learning-plan-step-editor { display: grid; gap: 8px; padding: 18px 0; margin-top: 10px; border-top: 1px solid #e2e8e6; }
.learning-plan-step-title { display: flex; justify-content: space-between; align-items: center; }
.learning-plan-step-title > div { display: flex; gap: 4px; }
.learning-plan-step-title button { color: #687d76; }
.learning-plan-step-title button:disabled { color: #cbd5e1; cursor: not-allowed; }
.form-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 12px; padding-top: 16px; border-top: 1px solid #e2e8e6; }
.learning-plan-version { margin-right: auto; }
@media (max-width: 1000px) { .learning-plan-draft-layout { grid-template-columns: 1fr; } .learning-plan-draft-list { border: 0 !important; padding: 0; } .learning-plan-items { max-height: 290px; } }
@media (max-width: 1024px) { .draft-save-bar, .draft-save-bar:global(body:has(.app-shell.sidebar-collapsed) .draft-save-bar) { left: 0; padding-inline: 20px; } }
@media (max-width: 620px) { .learning-plan-grid-fields { grid-template-columns: 1fr; } .learning-plan-editor { padding: 16px; } .form-actions .learning-plan-version, .draft-save-bar .learning-plan-version { width: 100%; } .draft-save-bar { gap: 10px; padding: 12px 16px calc(12px + env(safe-area-inset-bottom, 0px)); } .learning-plan-drafts-view { padding-bottom: calc(136px + env(safe-area-inset-bottom, 0px)); } }
</style>
