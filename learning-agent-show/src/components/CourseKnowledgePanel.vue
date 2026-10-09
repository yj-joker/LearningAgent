<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ArrowRight, CheckCircle2, CircleAlert, GripVertical, Link2, Lightbulb, LockKeyhole, Pencil, Plus, RefreshCw, Trash2 } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import { ApiError } from '@/api/client'
import { createKnowledgePointRelation } from '@/api/knowledgePointRelations'
import { createKnowledgePoints, deleteKnowledgePointsByIds, getCourseConfusableKnowledgePoints, getCoursePrerequisiteKnowledgePoints, getKnowledgePointsByChapterId, updateKnowledgePoints } from '@/api/knowledgePoints'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'
import type { ChapterVO, KnownCourse, KnowledgePointRelationType, KnowledgePointRelationVO, KnowledgePointVO } from '@/types/api'

const props = defineProps<{ course: KnownCourse; chapter: ChapterVO; blocked?: boolean }>()
const emit = defineEmits<{ 'state-change': [state: { dirty: boolean; busy: boolean }] }>()
const { currentUser } = useAuth()
const { showToast } = useToast()
const SORT_STEP = 1000
const MAX_SORT_ORDER = 4_294_967_295
let generation = 0
let relationsReadVersion = 0
let disposed = false

const panelElement = ref<HTMLElement | null>(null)
const knowledgePoints = ref<KnowledgePointVO[]>([])
const prerequisitePoints = ref<KnowledgePointVO[]>([])
const confusablePoints = ref<KnowledgePointVO[]>([])
const loadingPoints = ref(false)
const loadingRelations = ref(false)
const pointsLoaded = ref(false)
const loadError = ref('')
const relationLoadError = ref('')
const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingPoint = ref<KnowledgePointVO | null>(null)
const savingPoint = ref(false)
const editorForm = reactive({ name: '', description: '' })
const editorBaseline = ref({ name: '', description: '' })
const editorErrors = reactive({ name: '', description: '' })
const deletingPoint = ref<KnowledgePointVO | null>(null)
const deleting = ref(false)
const ordering = ref(false)
const dragFromIndex = ref<number | null>(null)
const dragPlacement = ref<{ index: number; after: boolean } | null>(null)
const pointerDrag = ref<{ pointerId: number; fromIndex: number; startX: number; startY: number; active: boolean } | null>(null)
const relationOpen = ref(false)
const creatingRelation = ref(false)
const relationForm = reactive<{ fromPointId: string; toPointId: string; relationType: KnowledgePointRelationType }>({ fromPointId: '', toPointId: '', relationType: 'PREREQUISITE' })
const relationBaseline = ref({ ...relationForm })
const relationError = ref('')
const recentRelations = ref<KnowledgePointRelationVO[]>([])

const activeCourseId = computed(() => props.course.courseId)
const activeChapterId = computed(() => props.chapter.id)
const sortedKnowledgePoints = computed(() => [...knowledgePoints.value].sort((a, b) => a.sortOrder - b.sortOrder))
const busy = computed(() => savingPoint.value || deleting.value || ordering.value || creatingRelation.value)
const disabled = computed(() => busy.value || props.blocked)
const editorDirty = computed(() => editorOpen.value && (editorForm.name !== editorBaseline.value.name || editorForm.description !== editorBaseline.value.description))
const relationDirty = computed(() => relationOpen.value && (relationForm.fromPointId !== relationBaseline.value.fromPointId || relationForm.toPointId !== relationBaseline.value.toPointId || relationForm.relationType !== relationBaseline.value.relationType))
const dirty = computed(() => editorDirty.value || relationDirty.value)
// 后端只限制非私有课程的顺序修改，名称、描述和关系建议继续沿用各自的权限校验。
const canReorder = computed(() => pointsLoaded.value && props.course.courseType === 'PRIVATE' && !disabled.value)
const canCreateRelation = computed(() => pointsLoaded.value && sortedKnowledgePoints.value.length > 1 && !disabled.value)
const relationFromPoint = computed(() => knowledgePoints.value.find(point => point.id === relationForm.fromPointId) ?? null)
const relationToPoint = computed(() => knowledgePoints.value.find(point => point.id === relationForm.toPointId) ?? null)
const uniquePrerequisitePoints = computed(() => uniquePoints(prerequisitePoints.value))
const uniqueConfusablePoints = computed(() => uniquePoints(confusablePoints.value))
const relationTypeLabels: Record<KnowledgePointRelationType, string> = { PREREQUISITE: '前置关系', CONFUSABLE: '易混淆关系' }
const relationStatusLabels: Record<KnowledgePointRelationVO['status'], string> = { PENDING: '待审核', ACTIVE: '已生效', REJECTED: '已拒绝', DEPRECATED: '已废弃' }

// 状态同步报告给课程编辑页，父级可以在切换章节或离开页面前统一保护未保存内容。
watch([dirty, busy], ([isDirty, isBusy]) => emit('state-change', { dirty: isDirty, busy: isBusy }), { immediate: true, flush: 'sync' })

function apiErrorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : error instanceof Error ? error.message : fallback
}

function pointName(pointId: string | number) {
  return knowledgePoints.value.find(point => String(point.id) === String(pointId))?.name ?? '未知知识点'
}

function uniquePoints(points: KnowledgePointVO[]) {
  const seen = new Set<string>()
  return points.filter(point => {
    const id = String(point.id)
    if (seen.has(id)) return false
    seen.add(id)
    return true
  })
}

// 每次请求固定所属课程、章节和登录身份，旧响应不能写回新章节或新账号。
function captureSelection() {
  return { generation, token: currentUser.value?.token, courseId: activeCourseId.value, chapterId: activeChapterId.value }
}

type SelectionSnapshot = ReturnType<typeof captureSelection>

function isCurrent(selection: SelectionSnapshot) {
  return !disposed && selection.generation === generation && selection.token === currentUser.value?.token
    && selection.courseId === activeCourseId.value && selection.chapterId === activeChapterId.value
}

function resetEditor() {
  editorOpen.value = false
  Object.assign(editorForm, editorBaseline.value)
  editingPoint.value = null
  editorErrors.name = ''
  editorErrors.description = ''
}

function resetRelationEditor() {
  relationOpen.value = false
  Object.assign(relationForm, relationBaseline.value)
  relationError.value = ''
}

// 父级已经确认丢弃后调用；正在保存时保持弹窗和编辑状态，避免误判为取消了请求。
function discardEdits() {
  if (busy.value) return false
  resetEditor()
  resetRelationEditor()
  deletingPoint.value = null
  clearDrag()
  return true
}

defineExpose({ discardEdits })

async function loadPoints(selection: SelectionSnapshot) {
  loadingPoints.value = true
  try {
    const loaded = await getKnowledgePointsByChapterId(selection.chapterId)
    if (!isCurrent(selection)) return
    knowledgePoints.value = loaded.sort((a, b) => a.sortOrder - b.sortOrder)
    pointsLoaded.value = true
  } catch (error) {
    if (isCurrent(selection)) loadError.value = apiErrorMessage(error, '获取知识点列表失败，请稍后重试')
  } finally {
    if (isCurrent(selection)) loadingPoints.value = false
  }
}

async function loadRelations(selection: SelectionSnapshot) {
  if (!isCurrent(selection)) return
  const version = ++relationsReadVersion
  loadingRelations.value = true
  relationLoadError.value = ''
  try {
    const [prerequisites, confusables] = await Promise.all([
      getCoursePrerequisiteKnowledgePoints(selection.courseId),
      getCourseConfusableKnowledgePoints(selection.courseId),
    ])
    if (!isCurrent(selection) || version !== relationsReadVersion) return
    prerequisitePoints.value = prerequisites
    confusablePoints.value = confusables
  } catch (error) {
    if (isCurrent(selection) && version === relationsReadVersion) relationLoadError.value = apiErrorMessage(error, '暂时无法加载课程关联知识点')
  } finally {
    if (isCurrent(selection) && version === relationsReadVersion) loadingRelations.value = false
  }
}

async function loadCurrentSelection(force: unknown = false) {
  // 手动刷新也不能清掉正在编辑或保存的表单。
  if ((force !== true && (disabled.value || dirty.value)) || disposed) return
  generation++
  relationsReadVersion++
  knowledgePoints.value = []
  prerequisitePoints.value = []
  confusablePoints.value = []
  recentRelations.value = []
  pointsLoaded.value = false
  loadError.value = ''
  relationLoadError.value = ''
  discardEdits()
  const selection = captureSelection()
  if (!selection.token) {
    loadingPoints.value = false
    loadingRelations.value = false
    loadError.value = '请先登录后查看章节知识点'
    return
  }
  // 两类读取互不依赖，关系读取失败不会挡住知识点编辑。
  await Promise.allSettled([loadPoints(selection), loadRelations(selection)])
}

function nextSortOrder() {
  const last = sortedKnowledgePoints.value[sortedKnowledgePoints.value.length - 1]
  return last ? last.sortOrder + SORT_STEP : SORT_STEP
}

function openCreateEditor() {
  if (!pointsLoaded.value || disabled.value) return
  if (nextSortOrder() > MAX_SORT_ORDER) {
    showToast('error', '无法添加知识点', '当前知识点顺序暂时无法继续扩展，请稍后重试')
    return
  }
  editorMode.value = 'create'
  editingPoint.value = null
  editorBaseline.value = { name: '', description: '' }
  Object.assign(editorForm, editorBaseline.value)
  editorErrors.name = ''
  editorErrors.description = ''
  editorOpen.value = true
}

function openEditEditor(point: KnowledgePointVO) {
  if (disabled.value || !pointsLoaded.value) return
  editorMode.value = 'edit'
  editingPoint.value = { ...point }
  editorBaseline.value = { name: point.name, description: point.description ?? '' }
  Object.assign(editorForm, editorBaseline.value)
  editorErrors.name = ''
  editorErrors.description = ''
  editorOpen.value = true
}

function closeEditor() {
  if (busy.value) return
  if (editorDirty.value && !window.confirm('知识点有未保存的修改，确定放弃这些修改吗？')) return
  resetEditor()
}

function openDelete(point: KnowledgePointVO) {
  if (!disabled.value) deletingPoint.value = { ...point }
}

function closeDelete() {
  if (!busy.value) deletingPoint.value = null
}

function openRelationEditor() {
  if (!canCreateRelation.value) return
  const [first, second] = sortedKnowledgePoints.value
  relationBaseline.value = { fromPointId: first?.id ?? '', toPointId: second?.id ?? '', relationType: 'PREREQUISITE' }
  Object.assign(relationForm, relationBaseline.value)
  relationError.value = ''
  relationOpen.value = true
}

function closeRelationEditor() {
  if (busy.value) return
  if (relationDirty.value && !window.confirm('关系建议有未保存的修改，确定放弃这些修改吗？')) return
  resetRelationEditor()
}

function validateRelation() {
  if (!relationFromPoint.value || !relationToPoint.value) relationError.value = '请选择关系两端的知识点'
  else if (relationForm.fromPointId === relationForm.toPointId) relationError.value = '关系两端不能选择同一个知识点'
  else relationError.value = ''
  return !relationError.value
}

async function saveRelation() {
  if (busy.value || !relationOpen.value || !canCreateRelation.value || !validateRelation()) return
  const selection = captureSelection()
  const payload = { ...relationForm }
  const description = `${relationFromPoint.value?.name ?? '知识点'} → ${relationToPoint.value?.name ?? '知识点'}`
  creatingRelation.value = true
  try {
    const created = await createKnowledgePointRelation(payload)
    if (!isCurrent(selection)) return
    recentRelations.value = [created, ...recentRelations.value].slice(0, 8)
    resetRelationEditor()
    showToast('success', '关系建议已提交', description)
    void loadRelations(selection)
  } catch (error) {
    if (isCurrent(selection)) relationError.value = apiErrorMessage(error, '关系提交失败，请稍后重试')
  } finally {
    if (isCurrent(selection)) creatingRelation.value = false
  }
}

function validateEditor() {
  const name = editorForm.name.trim()
  editorErrors.name = !name ? '请输入知识点名称' : name.length > 255 ? '知识点名称不能超过 255 个字符' : ''
  editorErrors.description = editorForm.description.length > 16_383 ? '知识点描述不能超过 16383 个字符' : ''
  return !editorErrors.name && !editorErrors.description
}

async function saveKnowledgePoint() {
  if (disabled.value || !editorOpen.value || !pointsLoaded.value || !validateEditor()) return
  const selection = captureSelection()
  const mode = editorMode.value
  const target = editingPoint.value ? { ...editingPoint.value } : null
  const name = editorForm.name.trim()
  const description = editorForm.description.trim() || null
  if (mode === 'edit' && target && target.name === name && (target.description ?? '') === (description ?? '')) {
    resetEditor()
    return
  }
  savingPoint.value = true
  try {
    if (mode === 'create') {
      const sortOrder = nextSortOrder()
      if (sortOrder > MAX_SORT_ORDER) throw new Error('当前知识点顺序暂时无法继续扩展')
      const created = await createKnowledgePoints([{ courseId: selection.courseId, chapterId: selection.chapterId, name, sortOrder, description }])
      if (!isCurrent(selection)) return
      knowledgePoints.value = [...knowledgePoints.value, ...created].sort((a, b) => a.sortOrder - b.sortOrder)
      showToast('success', '知识点已添加', `${name} 已添加到当前章节`)
    } else if (target) {
      const updated = await updateKnowledgePoints([{ id: target.id, courseId: selection.courseId, chapterId: selection.chapterId, name, sortOrder: target.sortOrder, description }])
      if (!isCurrent(selection)) return
      const saved = updated[0]
      knowledgePoints.value = knowledgePoints.value.map(point => point.id === target.id ? { ...point, ...(saved ?? { name, description }) } : point)
      showToast('success', '知识点已更新', name)
    } else return
    resetEditor()
    void loadRelations(selection)
  } catch (error) {
    if (isCurrent(selection)) showToast('error', mode === 'create' ? '添加知识点失败' : '修改知识点失败', apiErrorMessage(error, '发生未知错误'))
  } finally {
    if (isCurrent(selection)) savingPoint.value = false
  }
}

async function confirmDelete() {
  if (disabled.value || !deletingPoint.value) return
  const selection = captureSelection()
  const target = { ...deletingPoint.value }
  deleting.value = true
  try {
    await deleteKnowledgePointsByIds([target.id])
    if (!isCurrent(selection)) return
    knowledgePoints.value = knowledgePoints.value.filter(point => point.id !== target.id)
    deletingPoint.value = null
    showToast('success', '知识点已删除', target.name)
    void loadRelations(selection)
  } catch (error) {
    if (isCurrent(selection)) showToast('error', '删除知识点失败', apiErrorMessage(error, '发生未知错误'))
  } finally {
    if (isCurrent(selection)) deleting.value = false
  }
}

function calculateSortOrder(previous: KnowledgePointVO | undefined, next: KnowledgePointVO | undefined) {
  if (previous && next) {
    const gap = next.sortOrder - previous.sortOrder
    return gap > 1 ? previous.sortOrder + Math.floor(gap / 2) : null
  }
  if (previous) return previous.sortOrder <= MAX_SORT_ORDER - SORT_STEP ? previous.sortOrder + SORT_STEP : null
  if (next) {
    if (next.sortOrder === 0) return null
    return next.sortOrder >= SORT_STEP ? next.sortOrder - SORT_STEP : Math.floor(next.sortOrder / 2)
  }
  return SORT_STEP
}

async function reorderKnowledgePoint(fromIndex: number, insertionIndex: number) {
  if (!canReorder.value || fromIndex < 0 || fromIndex >= sortedKnowledgePoints.value.length) return
  const selection = captureSelection()
  const original = sortedKnowledgePoints.value.map(point => ({ ...point }))
  const reordered = original.map(point => ({ ...point }))
  const [moved] = reordered.splice(fromIndex, 1)
  if (!moved) return
  let targetIndex = Math.max(0, Math.min(insertionIndex, original.length))
  if (fromIndex < targetIndex) targetIndex -= 1
  reordered.splice(targetIndex, 0, moved)
  if (reordered.every((point, index) => point.id === original[index]?.id)) return
  const sortOrder = calculateSortOrder(reordered[targetIndex - 1], reordered[targetIndex + 1])
  if (sortOrder === null) {
    showToast('error', '暂时无法调整到该位置', '请刷新知识点列表后重试')
    return
  }
  reordered[targetIndex] = { ...moved, sortOrder }
  ordering.value = true
  knowledgePoints.value = reordered
  try {
    const updated = await updateKnowledgePoints([{ id: moved.id, courseId: selection.courseId, chapterId: selection.chapterId, name: moved.name, sortOrder, description: moved.description }])
    if (!isCurrent(selection)) return
    if (updated[0]) reordered[targetIndex] = updated[0]
    knowledgePoints.value = [...reordered].sort((a, b) => a.sortOrder - b.sortOrder)
    showToast('success', '知识点顺序已保存', `${moved.name} 已移动到新位置`)
  } catch (error) {
    if (!isCurrent(selection)) return
    knowledgePoints.value = original
    showToast('error', '排序保存失败，已恢复原顺序', apiErrorMessage(error, '请刷新知识点列表后重试'))
  } finally {
    if (isCurrent(selection)) ordering.value = false
  }
}

function onDragStart(event: DragEvent, index: number) {
  if (!canReorder.value || (event.target as HTMLElement | null)?.closest('.knowledge-point-actions')) {
    event.preventDefault()
    return
  }
  dragFromIndex.value = index
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', sortedKnowledgePoints.value[index]?.id ?? '')
  }
}

function onDragOver(event: DragEvent, index: number) {
  if (dragFromIndex.value === null || !canReorder.value) return
  event.preventDefault()
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  dragPlacement.value = { index, after: event.clientY > rect.top + rect.height / 2 }
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

async function onDrop(event: DragEvent, index: number) {
  event.preventDefault()
  const fromIndex = dragFromIndex.value
  const placement = dragPlacement.value ?? { index, after: false }
  clearDrag()
  if (fromIndex !== null) await reorderKnowledgePoint(fromIndex, placement.index + (placement.after ? 1 : 0))
}

function onPointerDragStart(event: PointerEvent, index: number) {
  if (!canReorder.value || (event.pointerType === 'mouse' && event.button !== 0)) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture?.(event.pointerId)
  pointerDrag.value = { pointerId: event.pointerId, fromIndex: index, startX: event.clientX, startY: event.clientY, active: false }
}

function onPointerDragMove(event: PointerEvent) {
  const state = pointerDrag.value
  if (!state || state.pointerId !== event.pointerId || !canReorder.value) return
  if (!state.active) {
    if (Math.hypot(event.clientX - state.startX, event.clientY - state.startY) < 6) return
    state.active = true
    dragFromIndex.value = state.fromIndex
  }
  event.preventDefault()
  const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('.knowledge-point-row')
  if (!target || !panelElement.value?.contains(target)) return
  const index = Number(target.dataset.knowledgePointIndex)
  if (!Number.isInteger(index)) return
  const rect = target.getBoundingClientRect()
  dragPlacement.value = { index, after: event.clientY > rect.top + rect.height / 2 }
}

async function onPointerDragEnd(event: PointerEvent) {
  const state = pointerDrag.value
  if (!state || state.pointerId !== event.pointerId) return
  const handle = event.currentTarget as HTMLElement
  if (handle.hasPointerCapture?.(event.pointerId)) handle.releasePointerCapture(event.pointerId)
  const placement = dragPlacement.value
  clearDrag()
  if (state.active && placement) await reorderKnowledgePoint(state.fromIndex, placement.index + (placement.after ? 1 : 0))
}

function onPointerDragCancel(event: PointerEvent) {
  const handle = event.currentTarget as HTMLElement
  if (handle.hasPointerCapture?.(event.pointerId)) handle.releasePointerCapture(event.pointerId)
  clearDrag()
}

function clearDrag() {
  pointerDrag.value = null
  dragFromIndex.value = null
  dragPlacement.value = null
}

watch(() => [props.course.courseId, props.chapter.id, currentUser.value?.token], () => {
  // 父级保护正常切换；强制换号或切换选择时仍立即隔离旧请求和旧弹窗。
  generation++
  savingPoint.value = false
  deleting.value = false
  ordering.value = false
  creatingRelation.value = false
  discardEdits()
  void loadCurrentSelection(true)
}, { immediate: true, flush: 'sync' })

onBeforeUnmount(() => {
  disposed = true
  generation++
  relationsReadVersion++
  emit('state-change', { dirty: false, busy: false })
})
</script>

<template>
  <section ref="panelElement" class="course-knowledge-panel">
    <header class="knowledge-workspace-header">
      <div class="knowledge-workspace-identity"><span><Lightbulb :size="22" /></span><div><small>{{ course.courseName }}</small><h3>{{ chapter.title }}</h3></div></div>
      <div class="knowledge-workspace-actions">
        <button class="icon-button" :disabled="disabled || dirty || loadingPoints" aria-label="刷新知识点" title="刷新知识点" @click="loadCurrentSelection"><RefreshCw :size="17" :class="{ spin: loadingPoints }" /></button>
        <button class="button button-secondary" :disabled="!canCreateRelation" @click="openRelationEditor"><Link2 :size="17" /> 建立关系</button>
        <button class="button button-primary" :disabled="disabled || !pointsLoaded" @click="openCreateEditor"><Plus :size="17" /> 添加知识点</button>
      </div>
    </header>
    <div v-if="loadingPoints" class="knowledge-loading" role="status"><RefreshCw :size="24" class="spin" /> 正在加载知识点…</div>
    <div v-else-if="loadError" class="chapter-load-error"><CircleAlert :size="24" /><div><strong>无法打开知识点列表</strong><p>{{ loadError }}</p></div><button class="button button-secondary" :disabled="disabled" @click="loadCurrentSelection">重试</button></div>
    <template v-else-if="pointsLoaded">
      <div v-if="course.courseType !== 'PRIVATE'" class="chapter-order-notice locked"><LockKeyhole :size="18" /><p>课程审核中或已发布，知识点顺序暂不可调整。</p></div>
      <div v-if="sortedKnowledgePoints.length" class="knowledge-point-list" :class="{ 'is-ordering': ordering }">
        <article v-for="(point, index) in sortedKnowledgePoints" :key="point.id" class="knowledge-point-row" :data-knowledge-point-index="index"
          :class="{ 'is-dragging': dragFromIndex === index, 'drop-before': dragPlacement?.index === index && !dragPlacement.after, 'drop-after': dragPlacement?.index === index && dragPlacement.after }"
          :draggable="canReorder" @dragstart="onDragStart($event, index)" @dragend="clearDrag" @dragover="onDragOver($event, index)" @drop="onDrop($event, index)">
          <span class="chapter-drag-handle" :class="{ disabled: !canReorder }" @pointerdown.stop="onPointerDragStart($event, index)" @pointermove.stop="onPointerDragMove" @pointerup.stop="onPointerDragEnd" @pointercancel.stop="onPointerDragCancel"><GripVertical :size="19" /></span>
          <span class="knowledge-point-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="knowledge-point-copy"><small>知识点 {{ index + 1 }}</small><h4>{{ point.name }}</h4><p>{{ point.description || '暂无描述' }}</p></div>
          <div class="knowledge-point-actions">
            <button :disabled="disabled" :aria-label="`修改 ${point.name}`" title="修改知识点" @click="openEditEditor(point)"><Pencil :size="16" /></button>
            <button :disabled="disabled" class="danger" :aria-label="`删除 ${point.name}`" title="删除知识点" @click="openDelete(point)"><Trash2 :size="16" /></button>
          </div>
        </article>
      </div>
      <EmptyState v-else title="这个章节还没有知识点" description="" />
      <section class="course-relation-panel">
        <header><div><h4>课程中的已生效关系</h4></div><button v-if="relationLoadError" class="icon-button" title="重新加载关系" aria-label="重新加载关系" :disabled="disabled" @click="loadRelations(captureSelection())"><RefreshCw :size="16" /></button></header>
        <div v-if="loadingRelations" class="relation-insight-loading"><RefreshCw :size="15" class="spin" /> 正在加载关联知识点…</div>
        <div v-else-if="relationLoadError" class="relation-insight-error"><CircleAlert :size="15" /> {{ relationLoadError }}</div>
        <div v-else class="course-relation-columns">
          <div class="course-relation-group"><div class="course-relation-group-title"><ArrowRight :size="15" /><strong>前置知识点</strong><span>{{ uniquePrerequisitePoints.length }}</span></div><div v-if="uniquePrerequisitePoints.length" class="course-relation-chips"><span v-for="point in uniquePrerequisitePoints" :key="point.id" class="course-relation-chip">{{ point.name }}</span></div><p v-else class="course-relation-empty">暂无已生效的前置关系</p></div>
          <div class="course-relation-group"><div class="course-relation-group-title mutual"><Link2 :size="15" /><strong>易混淆知识点</strong><span>{{ uniqueConfusablePoints.length }}</span></div><div v-if="uniqueConfusablePoints.length" class="course-relation-chips"><span v-for="point in uniqueConfusablePoints" :key="point.id" class="course-relation-chip">{{ point.name }}</span></div><p v-else class="course-relation-empty">暂无已生效的易混淆关系</p></div>
        </div>
      </section>
      <section v-if="recentRelations.length" class="recent-relation-panel"><header><h4>刚刚建立的关系</h4></header><div class="recent-relation-list"><article v-for="relation in recentRelations" :key="String(relation.id)"><CheckCircle2 :size="18" /><div class="relation-result-copy"><strong>{{ pointName(relation.fromPointId) }} → {{ pointName(relation.toPointId) }}</strong><small>{{ relationTypeLabels[relation.relationType] }} · {{ relationStatusLabels[relation.status] }}</small></div></article></div></section>
    </template>
    <ModalDialog :open="editorOpen" :title="editorMode === 'create' ? '添加知识点' : '修改知识点'" width="wide" @close="closeEditor">
      <form class="form-layout" @submit.prevent="saveKnowledgePoint"><fieldset class="course-editor-fields" :disabled="disabled">
        <div class="form-section"><label class="field-label" for="point-name">知识点名称 <b>*</b></label><input id="point-name" v-model="editorForm.name" class="form-input" maxlength="255"><span v-if="editorErrors.name" class="field-error">{{ editorErrors.name }}</span></div>
        <div class="form-section"><label class="field-label" for="point-description">描述</label><textarea id="point-description" v-model="editorForm.description" class="form-textarea" rows="5" maxlength="16383" /><span v-if="editorErrors.description" class="field-error">{{ editorErrors.description }}</span></div>
      </fieldset><footer class="form-actions"><button type="button" class="button button-secondary" :disabled="busy" @click="closeEditor">取消</button><button class="button button-primary" :disabled="disabled">{{ savingPoint ? '保存中…' : '保存知识点' }}</button></footer></form>
    </ModalDialog>
    <ModalDialog :open="Boolean(deletingPoint)" title="删除知识点" :description="`确认删除“${deletingPoint?.name ?? ''}”？`" @close="closeDelete"><footer class="form-actions"><button class="button button-secondary" :disabled="busy" @click="closeDelete">取消</button><button class="button chapter-delete-button" :disabled="disabled" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button></footer></ModalDialog>
    <ModalDialog :open="relationOpen" title="建立知识点关系" @close="closeRelationEditor">
      <form class="form-layout" @submit.prevent="saveRelation"><fieldset class="course-editor-fields" :disabled="disabled">
        <div class="form-section"><label class="field-label" for="relation-from">起点知识点</label><select id="relation-from" v-model="relationForm.fromPointId" class="form-select"><option v-for="point in sortedKnowledgePoints" :key="point.id" :value="point.id">{{ point.name }}</option></select></div>
        <div class="form-section"><label class="field-label" for="relation-to">目标知识点</label><select id="relation-to" v-model="relationForm.toPointId" class="form-select"><option v-for="point in sortedKnowledgePoints" :key="point.id" :value="point.id">{{ point.name }}</option></select></div>
        <div class="form-section"><label class="field-label" for="relation-type">关系类型</label><select id="relation-type" v-model="relationForm.relationType" class="form-select"><option value="PREREQUISITE">前置关系</option><option value="CONFUSABLE">易混淆关系</option></select></div>
        <span v-if="relationError" class="field-error">{{ relationError }}</span>
      </fieldset><footer class="form-actions"><button type="button" class="button button-secondary" :disabled="busy" @click="closeRelationEditor">取消</button><button class="button button-primary" :disabled="disabled">{{ creatingRelation ? '提交中…' : '提交关系建议' }}</button></footer></form>
    </ModalDialog>
  </section>
</template>

<style scoped>
.course-editor-fields { display: grid; gap: 18px; border: 0; margin: 0; padding: 0; min-width: 0; }
.knowledge-workspace-header { padding: 0 0 16px; border: 0; border-bottom: 1px solid var(--line); background: transparent; border-radius: 0; }
.course-relation-panel, .recent-relation-panel { padding: 18px 0 0; border: 0; border-top: 1px solid var(--line); background: transparent; border-radius: 0; }
.course-relation-group { padding: 0; border: 0; border-radius: 0; background: transparent; }
.knowledge-point-actions button:disabled { opacity: .5; cursor: default; }
.recent-relation-list article { gap: 10px; }
</style>
