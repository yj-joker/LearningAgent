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
import { createChapters, deleteChaptersByIds, getChaptersByCourseId, updateChapters } from '@/api/chapters'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'
import type { ChapterVO } from '@/types/api'

const SORT_STEP = 1000
const MAX_SORT_ORDER = 4_294_967_295

const route = useRoute()
const router = useRouter()
const { knownCourses } = useActivity()
const { showToast } = useToast()

const chapters = ref<ChapterVO[]>([])
const loading = ref(false)
const loaded = ref(false)
const loadError = ref('')
const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')
const editingChapter = ref<ChapterVO | null>(null)
const savingChapter = ref(false)
const deletingChapter = ref<ChapterVO | null>(null)
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
const editorForm = reactive({ title: '' })
const editorError = ref('')

const activeCourseId = computed(() => {
  const value = route.params.courseId
  return typeof value === 'string' ? value : ''
})
const knownCourse = computed(() => knownCourses.value.find((course) => course.courseId === activeCourseId.value) ?? null)
const courseStatus = computed(() => knownCourse.value?.courseType ?? null)
const courseName = computed(() => knownCourse.value?.courseName || '未选择课程')
const canReorder = computed(() => loaded.value && courseStatus.value === 'PRIVATE' && !ordering.value)
const sortedChapters = computed(() => [...chapters.value].sort((a, b) => a.sortOrder - b.sortOrder))

function apiErrorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

async function loadChapters() {
  const course = knownCourse.value
  if (!course) {
    loaded.value = false
    chapters.value = []
    return
  }

  loading.value = true
  loaded.value = false
  loadError.value = ''
  try {
    chapters.value = (await getChaptersByCourseId(course.courseId)).sort((a, b) => a.sortOrder - b.sortOrder)
    loaded.value = true
  } catch (error) {
    chapters.value = []
    loadError.value = apiErrorMessage(error, '获取章节列表失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openCourse(courseId: string) {
  router.push({ name: 'chapters', params: { courseId } })
}

function nextSortOrder() {
  const last = sortedChapters.value[sortedChapters.value.length - 1]
  return last ? last.sortOrder + SORT_STEP : SORT_STEP
}

function openCreateEditor() {
  const sortOrder = nextSortOrder()
  if (sortOrder > MAX_SORT_ORDER) {
    showToast('error', '无法添加章节', '当前章节顺序暂时无法继续扩展，请稍后重试')
    return
  }
  editorMode.value = 'create'
  editingChapter.value = null
  editorForm.title = ''
  editorError.value = ''
  editorOpen.value = true
}

function openEditEditor(chapter: ChapterVO) {
  editorMode.value = 'edit'
  editingChapter.value = chapter
  editorForm.title = chapter.title
  editorError.value = ''
  editorOpen.value = true
}

function validateTitle() {
  const title = editorForm.title.trim()
  editorError.value = !title ? '请输入章节标题' : title.length > 255 ? '章节标题不能超过 255 个字符' : ''
  return editorError.value ? null : title
}

async function saveChapter() {
  const title = validateTitle()
  if (!title || !activeCourseId.value) return

  if (editorMode.value === 'edit' && editingChapter.value?.title === title) {
    editorOpen.value = false
    return
  }

  savingChapter.value = true
  try {
    if (editorMode.value === 'create') {
      const sortOrder = nextSortOrder()
      if (sortOrder > MAX_SORT_ORDER) throw new Error('当前章节顺序暂时无法继续扩展')
      const created = await createChapters([{
        title,
        courseId: activeCourseId.value,
        sortOrder,
      }])
      chapters.value = [...chapters.value, ...created].sort((a, b) => a.sortOrder - b.sortOrder)
      showToast('success', '章节已添加', `${title} 已添加到课程末尾`)
    } else if (editingChapter.value) {
      const updated = await updateChapters([{
        id: editingChapter.value.id,
        title,
        courseId: activeCourseId.value,
        sortOrder: editingChapter.value.sortOrder,
      }])
      const saved = updated[0]
      chapters.value = chapters.value.map((chapter) => chapter.id === editingChapter.value?.id
        ? { ...chapter, ...(saved ?? { title }) }
        : chapter)
      showToast('success', '章节已更新', title)
    }
    editorOpen.value = false
  } catch (error) {
    showToast('error', editorMode.value === 'create' ? '添加章节失败' : '修改章节失败', apiErrorMessage(error, error instanceof Error ? error.message : '发生未知错误'))
  } finally {
    savingChapter.value = false
  }
}

async function confirmDelete() {
  if (!deletingChapter.value) return
  const target = deletingChapter.value
  deleting.value = true
  try {
    await deleteChaptersByIds([target.id])
    chapters.value = chapters.value.filter((chapter) => chapter.id !== target.id)
    deletingChapter.value = null
    showToast('success', '章节已删除', target.title)
  } catch (error) {
    showToast('error', '删除章节失败', apiErrorMessage(error, '发生未知错误'))
  } finally {
    deleting.value = false
  }
}

function calculateSortOrder(previous: ChapterVO | undefined, next: ChapterVO | undefined) {
  if (previous && next) {
    const gap = next.sortOrder - previous.sortOrder
    return gap > 1 ? previous.sortOrder + Math.floor(gap / 2) : null
  }
  if (previous) {
    return previous.sortOrder <= MAX_SORT_ORDER - SORT_STEP ? previous.sortOrder + SORT_STEP : null
  }
  if (next) {
    if (next.sortOrder === 0) return null
    return next.sortOrder >= SORT_STEP ? next.sortOrder - SORT_STEP : Math.floor(next.sortOrder / 2)
  }
  return SORT_STEP
}

async function reorderChapter(fromIndex: number, insertionIndex: number) {
  if (!canReorder.value || fromIndex < 0 || fromIndex >= sortedChapters.value.length) return

  const original = sortedChapters.value.map((chapter) => ({ ...chapter }))
  const reordered = original.map((chapter) => ({ ...chapter }))
  const [moved] = reordered.splice(fromIndex, 1)
  if (!moved) return

  let targetIndex = Math.max(0, Math.min(insertionIndex, original.length))
  if (fromIndex < targetIndex) targetIndex -= 1
  reordered.splice(targetIndex, 0, moved)

  if (reordered.every((chapter, index) => chapter.id === original[index]?.id)) return

  const sortOrder = calculateSortOrder(reordered[targetIndex - 1], reordered[targetIndex + 1])
  if (sortOrder === null) {
    showToast('error', '暂时无法调整到该位置', '请刷新章节列表后重试')
    return
  }

  reordered[targetIndex] = { ...moved, sortOrder }
  chapters.value = reordered
  ordering.value = true
  try {
    const updated = await updateChapters([{
      id: moved.id,
      title: moved.title,
      courseId: activeCourseId.value,
      sortOrder,
    }])
    const saved = updated[0]
    if (saved) reordered[targetIndex] = saved
    chapters.value = [...reordered].sort((a, b) => a.sortOrder - b.sortOrder)
    showToast('success', '章节顺序已保存', `${moved.title} 已移动到新位置`)
  } catch (error) {
    chapters.value = original
    showToast('error', '排序保存失败，已恢复原顺序', apiErrorMessage(error, '请刷新章节列表后重试'))
  } finally {
    ordering.value = false
  }
}

function onDragStart(event: DragEvent, index: number) {
  if (!canReorder.value) {
    event.preventDefault()
    return
  }
  if ((event.target as HTMLElement | null)?.closest('.chapter-row-actions')) {
    event.preventDefault()
    return
  }
  dragFromIndex.value = index
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', sortedChapters.value[index]?.id ?? '')
  }
}

function onDragOver(event: DragEvent, index: number) {
  if (dragFromIndex.value === null || !canReorder.value) return
  event.preventDefault()
  const element = event.currentTarget as HTMLElement
  const rect = element.getBoundingClientRect()
  dragPlacement.value = { index, after: event.clientY > rect.top + rect.height / 2 }
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

async function onDrop(event: DragEvent, index: number) {
  event.preventDefault()
  const fromIndex = dragFromIndex.value
  const placement = dragPlacement.value ?? { index, after: false }
  clearDrag()
  if (fromIndex === null) return
  await reorderChapter(fromIndex, placement.index + (placement.after ? 1 : 0))
}

function onPointerDragStart(event: PointerEvent, index: number) {
  if (!canReorder.value || (event.pointerType === 'mouse' && event.button !== 0)) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture?.(event.pointerId)
  pointerDrag.value = {
    pointerId: event.pointerId,
    fromIndex: index,
    startX: event.clientX,
    startY: event.clientY,
    active: false,
  }
}

function onPointerDragMove(event: PointerEvent) {
  const state = pointerDrag.value
  if (!state || state.pointerId !== event.pointerId || !canReorder.value) return

  if (!state.active) {
    const distance = Math.hypot(event.clientX - state.startX, event.clientY - state.startY)
    if (distance < 6) return
    state.active = true
    dragFromIndex.value = state.fromIndex
  }

  event.preventDefault()
  const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('.chapter-row')
  if (!target) return
  const index = Number(target.dataset.chapterIndex)
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
  await reorderChapter(state.fromIndex, placement.index + (placement.after ? 1 : 0))
}

function clearDrag() {
  dragFromIndex.value = null
  dragPlacement.value = null
}

watch(() => route.params.courseId, (value) => {
  const courseId = typeof value === 'string' ? value : ''
  chapters.value = []
  loaded.value = false
  loadError.value = ''
  if (!courseId) return
  if (!knownCourses.value.some((course) => course.courseId === courseId)) {
    showToast('error', '无法打开课程', '请从你的课程列表中选择需要编排的课程')
    router.replace({ name: 'chapters' })
    return
  }
  loadChapters()
}, { immediate: true })
</script>

<template>
  <div class="chapters-view">
    <section class="page-heading chapter-page-heading">
      <div>
        <span class="section-kicker">课程内容</span>
        <h2>章节编排</h2>
        <p>选择课程后添加章节，私有课程可以直接拖动调整顺序。</p>
      </div>
      <button v-if="activeCourseId" class="button button-secondary" @click="router.push({ name: 'chapters' })"><ChevronLeft :size="17" /> 返回课程选择</button>
    </section>

    <section v-if="!activeCourseId" class="chapter-course-selection">
      <header class="resource-section-header"><div><h3>选择课程</h3><p>选择一门课程开始编排</p></div></header>
      <div v-if="knownCourses.length" class="chapter-course-grid">
        <button v-for="course in knownCourses" :key="course.courseId" @click="openCourse(course.courseId)">
          <span><BookOpenText :size="21" /></span>
          <div><strong>{{ course.courseName }}</strong><small>{{ course.courseType === 'PRIVATE' ? '可以拖动排序' : '当前顺序已锁定' }}</small></div>
          <StatusBadge :status="course.courseType" />
          <ArrowRight :size="17" />
        </button>
      </div>
      <EmptyState v-else title="还没有可编排的课程" description="请先创建一门课程，再回来添加章节。" />
    </section>

    <section v-else class="chapter-workspace">

      <div v-if="loading" class="chapter-loading" aria-live="polite">
        <RefreshCw :size="25" class="spin" />
        <span>正在加载章节…</span>
      </div>

      <div v-else-if="loadError" class="chapter-load-error">
        <span><CircleAlert :size="24" /></span>
        <div><strong>无法打开课程章节</strong><p>{{ loadError }}</p></div>
        <button class="button button-secondary" @click="loadChapters">重新加载</button>
      </div>

      <template v-else-if="loaded">
        <header class="chapter-workspace-header">
          <div class="chapter-course-identity">
            <span><BookOpenText :size="22" /></span>
            <div>
              <small>正在编辑</small>
              <h3>{{ courseName }}</h3>
            </div>
          </div>
          <div class="chapter-workspace-actions">
            <StatusBadge v-if="courseStatus" :status="courseStatus" />
            <button class="icon-button" :disabled="loading || ordering" aria-label="刷新章节" title="刷新章节" @click="loadChapters"><RefreshCw :size="17" :class="{ spin: loading }" /></button>
            <button class="button button-primary" @click="openCreateEditor"><Plus :size="17" /> 添加章节</button>
          </div>
        </header>

        <div class="chapter-order-notice" :class="{ locked: !canReorder }">
          <GripVertical v-if="canReorder" :size="18" />
          <LockKeyhole v-else :size="18" />
          <p v-if="canReorder">按住章节卡片拖到目标位置，松开后会自动保存新顺序。</p>
          <p v-else>课程审核中或已发布，章节顺序暂不可调整。</p>
        </div>

        <div v-if="sortedChapters.length" class="chapter-list" :class="{ 'is-ordering': ordering }">
          <article
            v-for="(chapter, index) in sortedChapters"
            :key="chapter.id"
            class="chapter-row"
            :data-chapter-index="index"
            :class="{
              'is-dragging': dragFromIndex === index,
              'drop-before': dragPlacement?.index === index && !dragPlacement.after,
              'drop-after': dragPlacement?.index === index && dragPlacement.after,
            }"
            :draggable="canReorder"
            :aria-label="canReorder ? `拖动第 ${index + 1} 章调整顺序` : undefined"
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
            <span class="chapter-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="chapter-row-copy">
              <small>CHAPTER {{ index + 1 }}</small>
              <h4>{{ chapter.title }}</h4>
            </div>
            <div class="chapter-row-actions">
              <button :draggable="false" :aria-label="`管理 ${chapter.title} 的知识点`" title="管理知识点" @click="router.push({ name: 'knowledge-points', params: { courseId: activeCourseId, chapterId: chapter.id } })"><Lightbulb :size="16" /></button>
              <button :draggable="false" :aria-label="`修改 ${chapter.title}`" title="修改章节" @click="openEditEditor(chapter)"><Pencil :size="16" /></button>
              <button :draggable="false" class="danger" :aria-label="`删除 ${chapter.title}`" title="删除章节" @click="deletingChapter = chapter"><Trash2 :size="16" /></button>
            </div>
          </article>
        </div>

        <EmptyState
          v-else
          title="这门课程还没有章节"
          description="添加第一个章节，开始编排课程内容。"
        />
      </template>
    </section>

    <ModalDialog
      :open="editorOpen"
      :title="editorMode === 'create' ? '添加章节' : '修改章节'"
      :description="editorMode === 'create' ? '新章节会添加到当前课程末尾。' : '修改章节标题不会改变现有顺序。'"
      @close="editorOpen = false"
    >
      <form class="form-layout" @submit.prevent="saveChapter">
        <div class="form-section">
          <div class="label-row"><label class="field-label" for="chapter-title">章节标题 <b>*</b></label><span>{{ editorForm.title.length }}/255</span></div>
          <input id="chapter-title" v-model="editorForm.title" class="form-input" maxlength="255" placeholder="例如：线程与并发基础" autofocus @input="editorError = ''">
          <span v-if="editorError" class="field-error">{{ editorError }}</span>
        </div>
        <footer class="form-actions">
          <button type="button" class="button button-secondary" @click="editorOpen = false">取消</button>
          <button class="button button-primary" :disabled="savingChapter">{{ savingChapter ? '保存中…' : '保存章节' }} <ArrowRight v-if="!savingChapter" :size="16" /></button>
        </footer>
      </form>
    </ModalDialog>

    <ModalDialog
      :open="Boolean(deletingChapter)"
      title="删除章节"
      :description="deletingChapter ? `确认删除“${deletingChapter.title}”？` : ''"
      @close="deletingChapter = null"
    >
      <div class="delete-chapter-confirm">
        <span><Trash2 :size="23" /></span>
        <p>删除后无法恢复，请确认不再需要该章节。</p>
      </div>
      <footer class="form-actions">
        <button type="button" class="button button-secondary" @click="deletingChapter = null">取消</button>
        <button class="button chapter-delete-button" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button>
      </footer>
    </ModalDialog>
  </div>
</template>
