<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  BookOpenText,
  ChevronLeft,
  CircleAlert,
  GripVertical,
  Lightbulb,
  LockKeyhole,
  Pencil,
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { getChaptersByCourseId } from '@/api/chapters'
import {
  createKnowledgePoints,
  deleteKnowledgePointsByIds,
  getKnowledgePointsByChapterId,
  updateKnowledgePoints,
} from '@/api/knowledgePoints'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'
import type { ChapterVO, KnowledgePointVO } from '@/types/api'

const SORT_STEP = 1000
const MAX_SORT_ORDER = 4_294_967_295

const route = useRoute()
const router = useRouter()
const { knownCourses } = useActivity()
const { showToast } = useToast()

const chapters = ref<ChapterVO[]>([])
const knowledgePoints = ref<KnowledgePointVO[]>([])
const loadingChapters = ref(false)
const loadingPoints = ref(false)
const pointsLoaded = ref(false)
const loadError = ref('')

const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingPoint = ref<KnowledgePointVO | null>(null)
const savingPoint = ref(false)
const editorForm = reactive({ name: '', description: '' })
const editorErrors = reactive({ name: '', description: '' })

const deletingPoint = ref<KnowledgePointVO | null>(null)
const deleting = ref(false)
const ordering = ref(false)
const dragFromIndex = ref<number | null>(null)
const dragPlacement = ref<{ index: number; after: boolean } | null>(null)
const pointerDrag = ref<{
  pointerId: number
  fromIndex: number
  startX: number
  startY: number
  active: boolean
} | null>(null)

const activeCourseId = computed(() => typeof route.params.courseId === 'string' ? route.params.courseId : '')
const activeChapterId = computed(() => typeof route.params.chapterId === 'string' ? route.params.chapterId : '')
const knownCourse = computed(() => knownCourses.value.find((course) => course.courseId === activeCourseId.value) ?? null)
const selectedChapter = computed(() => chapters.value.find((chapter) => chapter.id === activeChapterId.value) ?? null)
const sortedChapters = computed(() => [...chapters.value].sort((a, b) => a.sortOrder - b.sortOrder))
const sortedKnowledgePoints = computed(() => [...knowledgePoints.value].sort((a, b) => a.sortOrder - b.sortOrder))
const canReorder = computed(() => pointsLoaded.value && knownCourse.value?.courseType === 'PRIVATE' && !ordering.value)

function apiErrorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

function openCourse(courseId: string) {
  router.push({ name: 'knowledge-points', params: { courseId } })
}

function openChapter(chapterId: string) {
  router.push({ name: 'knowledge-points', params: { courseId: activeCourseId.value, chapterId } })
}

function goBack() {
  if (activeChapterId.value) {
    router.push({ name: 'knowledge-points', params: { courseId: activeCourseId.value } })
  } else {
    router.push({ name: 'knowledge-points' })
  }
}

async function loadCurrentSelection() {
  const courseId = activeCourseId.value
  const chapterId = activeChapterId.value
  chapters.value = []
  knowledgePoints.value = []
  pointsLoaded.value = false
  loadError.value = ''

  if (!courseId) return
  if (!knownCourses.value.some((course) => course.courseId === courseId)) {
    showToast('error', '无法打开课程', '请从你的课程列表中选择需要管理的课程')
    await router.replace({ name: 'knowledge-points' })
    return
  }

  loadingChapters.value = true
  try {
    const loadedChapters = (await getChaptersByCourseId(courseId)).sort((a, b) => a.sortOrder - b.sortOrder)
    if (courseId !== activeCourseId.value) return
    chapters.value = loadedChapters
  } catch (error) {
    loadError.value = apiErrorMessage(error, '获取章节列表失败，请稍后重试')
    return
  } finally {
    loadingChapters.value = false
  }

  if (!chapterId) return
  if (!chapters.value.some((chapter) => chapter.id === chapterId)) {
    showToast('error', '无法打开章节', '请从课程章节列表中选择需要管理的章节')
    await router.replace({ name: 'knowledge-points', params: { courseId } })
    return
  }

  loadingPoints.value = true
  try {
    const loadedPoints = (await getKnowledgePointsByChapterId(chapterId)).sort((a, b) => a.sortOrder - b.sortOrder)
    if (courseId !== activeCourseId.value || chapterId !== activeChapterId.value) return
    knowledgePoints.value = loadedPoints
    pointsLoaded.value = true
  } catch (error) {
    loadError.value = apiErrorMessage(error, '获取知识点列表失败，请稍后重试')
  } finally {
    loadingPoints.value = false
  }
}

function nextSortOrder() {
  const last = sortedKnowledgePoints.value[sortedKnowledgePoints.value.length - 1]
  return last ? last.sortOrder + SORT_STEP : SORT_STEP
}

function openCreateEditor() {
  if (!selectedChapter.value) return
  if (nextSortOrder() > MAX_SORT_ORDER) {
    showToast('error', '无法添加知识点', '当前知识点顺序暂时无法继续扩展，请稍后重试')
    return
  }
  editorMode.value = 'create'
  editingPoint.value = null
  editorForm.name = ''
  editorForm.description = ''
  editorErrors.name = ''
  editorErrors.description = ''
  editorOpen.value = true
}

function openEditEditor(point: KnowledgePointVO) {
  editorMode.value = 'edit'
  editingPoint.value = point
  editorForm.name = point.name
  editorForm.description = point.description ?? ''
  editorErrors.name = ''
  editorErrors.description = ''
  editorOpen.value = true
}

function validateEditor() {
  const name = editorForm.name.trim()
  editorErrors.name = !name ? '请输入知识点名称' : name.length > 255 ? '知识点名称不能超过 255 个字符' : ''
  editorErrors.description = editorForm.description.length > 16_383 ? '知识点描述不能超过 16383 个字符' : ''
  return !editorErrors.name && !editorErrors.description
}

async function saveKnowledgePoint() {
  if (!validateEditor() || !activeCourseId.value || !activeChapterId.value) return
  const name = editorForm.name.trim()
  const description = editorForm.description.trim() || null

  if (editorMode.value === 'edit' && editingPoint.value
    && editingPoint.value.name === name
    && (editingPoint.value.description ?? '') === (description ?? '')) {
    editorOpen.value = false
    return
  }

  savingPoint.value = true
  try {
    if (editorMode.value === 'create') {
      const sortOrder = nextSortOrder()
      if (sortOrder > MAX_SORT_ORDER) throw new Error('当前知识点顺序暂时无法继续扩展')
      const created = await createKnowledgePoints([{
        courseId: activeCourseId.value,
        chapterId: activeChapterId.value,
        name,
        sortOrder,
        description,
      }])
      knowledgePoints.value = [...knowledgePoints.value, ...created].sort((a, b) => a.sortOrder - b.sortOrder)
      showToast('success', '知识点已添加', `${name} 已添加到当前章节`)
    } else if (editingPoint.value) {
      const updated = await updateKnowledgePoints([{
        id: editingPoint.value.id,
        courseId: activeCourseId.value,
        chapterId: activeChapterId.value,
        name,
        sortOrder: editingPoint.value.sortOrder,
        description,
      }])
      const saved = updated[0]
      knowledgePoints.value = knowledgePoints.value.map((point) => point.id === editingPoint.value?.id
        ? { ...point, ...(saved ?? { name, description }) }
        : point)
      showToast('success', '知识点已更新', name)
    }
    editorOpen.value = false
  } catch (error) {
    showToast('error', editorMode.value === 'create' ? '添加知识点失败' : '修改知识点失败', apiErrorMessage(error, error instanceof Error ? error.message : '发生未知错误'))
  } finally {
    savingPoint.value = false
  }
}

async function confirmDelete() {
  if (!deletingPoint.value) return
  const target = deletingPoint.value
  deleting.value = true
  try {
    await deleteKnowledgePointsByIds([target.id])
    knowledgePoints.value = knowledgePoints.value.filter((point) => point.id !== target.id)
    deletingPoint.value = null
    showToast('success', '知识点已删除', target.name)
  } catch (error) {
    showToast('error', '删除知识点失败', apiErrorMessage(error, '发生未知错误'))
  } finally {
    deleting.value = false
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

  const original = sortedKnowledgePoints.value.map((point) => ({ ...point }))
  const reordered = original.map((point) => ({ ...point }))
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
  knowledgePoints.value = reordered
  ordering.value = true
  try {
    const updated = await updateKnowledgePoints([{
      id: moved.id,
      courseId: activeCourseId.value,
      chapterId: activeChapterId.value,
      name: moved.name,
      sortOrder,
      description: moved.description,
    }])
    const saved = updated[0]
    if (saved) reordered[targetIndex] = saved
    knowledgePoints.value = [...reordered].sort((a, b) => a.sortOrder - b.sortOrder)
    showToast('success', '知识点顺序已保存', `${moved.name} 已移动到新位置`)
  } catch (error) {
    knowledgePoints.value = original
    showToast('error', '排序保存失败，已恢复原顺序', apiErrorMessage(error, '请刷新知识点列表后重试'))
  } finally {
    ordering.value = false
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
  if (fromIndex === null) return
  await reorderKnowledgePoint(fromIndex, placement.index + (placement.after ? 1 : 0))
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
  if (!target) return
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
  pointerDrag.value = null
  clearDrag()
  if (!state.active || !placement) return
  await reorderKnowledgePoint(state.fromIndex, placement.index + (placement.after ? 1 : 0))
}

function clearDrag() {
  dragFromIndex.value = null
  dragPlacement.value = null
}

watch(() => [route.params.courseId, route.params.chapterId], loadCurrentSelection, { immediate: true })
</script>

<template>
  <div class="knowledge-points-view">
    <section class="page-heading">
      <div>
        <span class="section-kicker">课程内容</span>
        <h2>知识点管理</h2>
        <p>在章节中添加和整理知识点，私有课程可以直接拖动调整顺序。</p>
      </div>
      <button v-if="activeCourseId" class="button button-secondary" @click="goBack"><ChevronLeft :size="17" /> {{ activeChapterId ? '返回章节选择' : '返回课程选择' }}</button>
    </section>

    <section v-if="!activeCourseId" class="knowledge-selection-section">
      <header class="resource-section-header"><div><h3>选择课程</h3><p>选择一门课程继续管理知识点</p></div></header>
      <div v-if="knownCourses.length" class="chapter-course-grid">
        <button v-for="course in knownCourses" :key="course.courseId" @click="openCourse(course.courseId)">
          <span><BookOpenText :size="21" /></span>
          <div><strong>{{ course.courseName }}</strong><small>查看课程章节</small></div>
          <StatusBadge :status="course.courseType" />
          <ArrowRight :size="17" />
        </button>
      </div>
      <EmptyState v-else title="还没有课程" description="请先创建一门课程，再添加章节和知识点。" />
    </section>

    <section v-else-if="loadingChapters" class="knowledge-loading" aria-live="polite"><RefreshCw :size="24" class="spin" /><span>正在加载章节…</span></section>

    <section v-else-if="loadError && !activeChapterId" class="chapter-load-error">
      <span><CircleAlert :size="24" /></span><div><strong>无法打开课程章节</strong><p>{{ loadError }}</p></div>
      <button class="button button-secondary" @click="loadCurrentSelection">重新加载</button>
    </section>

    <section v-else-if="!activeChapterId" class="knowledge-selection-section">
      <header class="resource-section-header"><div><h3>{{ knownCourse?.courseName }}</h3><p>选择需要管理知识点的章节</p></div><StatusBadge v-if="knownCourse" :status="knownCourse.courseType" /></header>
      <div v-if="sortedChapters.length" class="knowledge-chapter-grid">
        <button v-for="(chapter, index) in sortedChapters" :key="chapter.id" @click="openChapter(chapter.id)">
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          <div><small>第 {{ index + 1 }} 章</small><strong>{{ chapter.title }}</strong></div>
          <ArrowRight :size="17" />
        </button>
      </div>
      <EmptyState v-else title="这门课程还没有章节" description="请先在章节编排中添加章节。" />
    </section>

    <section v-else class="knowledge-workspace">
      <div v-if="loadingPoints" class="knowledge-loading" aria-live="polite"><RefreshCw :size="24" class="spin" /><span>正在加载知识点…</span></div>

      <div v-else-if="loadError" class="chapter-load-error">
        <span><CircleAlert :size="24" /></span><div><strong>无法打开知识点列表</strong><p>{{ loadError }}</p></div>
        <button class="button button-secondary" @click="loadCurrentSelection">重新加载</button>
      </div>

      <template v-else-if="pointsLoaded && selectedChapter">
        <header class="knowledge-workspace-header">
          <div class="knowledge-workspace-identity">
            <span><Lightbulb :size="22" /></span>
            <div><small>{{ knownCourse?.courseName }}</small><h3>{{ selectedChapter.title }}</h3></div>
          </div>
          <div class="knowledge-workspace-actions">
            <StatusBadge v-if="knownCourse" :status="knownCourse.courseType" />
            <button class="icon-button" :disabled="loadingPoints || ordering" aria-label="刷新知识点" title="刷新知识点" @click="loadCurrentSelection"><RefreshCw :size="17" :class="{ spin: loadingPoints }" /></button>
            <button class="button button-primary" @click="openCreateEditor"><Plus :size="17" /> 添加知识点</button>
          </div>
        </header>

        <div class="chapter-order-notice" :class="{ locked: !canReorder }">
          <GripVertical v-if="canReorder" :size="18" /><LockKeyhole v-else :size="18" />
          <p v-if="canReorder">按住知识点卡片拖到目标位置，松开后会自动保存新顺序。</p>
          <p v-else>课程审核中或已发布，知识点顺序暂不可调整。</p>
        </div>

        <div v-if="sortedKnowledgePoints.length" class="knowledge-point-list" :class="{ 'is-ordering': ordering }">
          <article
            v-for="(point, index) in sortedKnowledgePoints"
            :key="point.id"
            class="knowledge-point-row"
            :data-knowledge-point-index="index"
            :class="{
              'is-dragging': dragFromIndex === index,
              'drop-before': dragPlacement?.index === index && !dragPlacement.after,
              'drop-after': dragPlacement?.index === index && dragPlacement.after,
            }"
            :draggable="canReorder"
            :aria-label="canReorder ? `拖动第 ${index + 1} 个知识点调整顺序` : undefined"
            @dragstart="onDragStart($event, index)"
            @dragend="clearDrag"
            @dragover="onDragOver($event, index)"
            @drop="onDrop($event, index)"
          >
            <span
              class="chapter-drag-handle"
              :class="{ disabled: !canReorder }"
              aria-hidden="true"
              @pointerdown.stop="onPointerDragStart($event, index)"
              @pointermove.stop="onPointerDragMove"
              @pointerup.stop="onPointerDragEnd"
              @pointercancel.stop="onPointerDragEnd"
            ><GripVertical :size="19" /></span>
            <span class="knowledge-point-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="knowledge-point-copy"><small>知识点 {{ index + 1 }}</small><h4>{{ point.name }}</h4><p>{{ point.description || '暂无描述' }}</p></div>
            <div class="knowledge-point-actions">
              <button :draggable="false" :aria-label="`修改 ${point.name}`" title="修改知识点" @click="openEditEditor(point)"><Pencil :size="16" /></button>
              <button :draggable="false" class="danger" :aria-label="`删除 ${point.name}`" title="删除知识点" @click="deletingPoint = point"><Trash2 :size="16" /></button>
            </div>
          </article>
        </div>

        <EmptyState v-else title="这个章节还没有知识点" description="添加第一个知识点，开始整理章节内容。" />
      </template>
    </section>

    <ModalDialog
      :open="editorOpen"
      :title="editorMode === 'create' ? '添加知识点' : '修改知识点'"
      :description="editorMode === 'create' ? '新知识点会添加到当前章节末尾。' : '修改名称和描述不会改变现有顺序。'"
      width="wide"
      @close="editorOpen = false"
    >
      <form class="form-layout" @submit.prevent="saveKnowledgePoint">
        <div class="form-section">
          <div class="label-row"><label class="field-label" for="knowledge-point-name">知识点名称 <b>*</b></label><span>{{ editorForm.name.length }}/255</span></div>
          <input id="knowledge-point-name" v-model="editorForm.name" class="form-input" maxlength="255" placeholder="例如：JVM 内存结构" autofocus @input="editorErrors.name = ''">
          <span v-if="editorErrors.name" class="field-error">{{ editorErrors.name }}</span>
        </div>
        <div class="form-section">
          <div class="label-row"><label class="field-label" for="knowledge-point-description">知识点描述</label><span>{{ editorForm.description.length }}/16383</span></div>
          <textarea id="knowledge-point-description" v-model="editorForm.description" class="form-textarea knowledge-description-input" rows="6" maxlength="16383" placeholder="记录概念定义、学习重点或需要掌握的问题" @input="editorErrors.description = ''" />
          <span v-if="editorErrors.description" class="field-error">{{ editorErrors.description }}</span>
        </div>
        <footer class="form-actions">
          <button type="button" class="button button-secondary" @click="editorOpen = false">取消</button>
          <button class="button button-primary" :disabled="savingPoint">{{ savingPoint ? '保存中…' : '保存知识点' }} <ArrowRight v-if="!savingPoint" :size="16" /></button>
        </footer>
      </form>
    </ModalDialog>

    <ModalDialog
      :open="Boolean(deletingPoint)"
      title="删除知识点"
      :description="deletingPoint ? `确认删除“${deletingPoint.name}”？` : ''"
      @close="deletingPoint = null"
    >
      <div class="delete-chapter-confirm"><span><Trash2 :size="23" /></span><p>删除后无法恢复，请确认不再需要这个知识点。</p></div>
      <footer class="form-actions">
        <button type="button" class="button button-secondary" @click="deletingPoint = null">取消</button>
        <button class="button chapter-delete-button" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button>
      </footer>
    </ModalDialog>
  </div>
</template>
