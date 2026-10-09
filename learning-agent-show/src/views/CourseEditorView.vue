<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowUp, ChevronLeft, CircleAlert, GripVertical, ListTree, Pencil, Plus, RefreshCw, Send, Trash2 } from 'lucide-vue-next'
import CourseKnowledgePanel from '@/components/CourseKnowledgePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { getCourse, publishCourse } from '@/api/courses'
import { createChapters, deleteChaptersByIds, getChaptersByCourseId, updateChapters } from '@/api/chapters'
import { useCourses } from '@/composables/useCourses'
import { useAuth } from '@/composables/useAuth'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'
import { getStoredToken } from '@/utils/authStorage'
import type { ChapterVO, CourseVO, KnownCourse } from '@/types/api'

const route = useRoute()
const router = useRouter()
const { currentUser } = useAuth()
const { upsertCourse } = useCourses()
const { addActivity } = useActivity()
const { showToast } = useToast()
const courseId = computed(() => String(route.params.courseId ?? ''))
const course = ref<CourseVO | null>(null)
const chapters = ref<ChapterVO[]>([])
const selectedId = ref('')
const selectedChapter = computed(() => chapters.value.find(chapter => chapter.id === selectedId.value) ?? null)
const knownCourse = computed<KnownCourse | null>(() => course.value ? { courseId: String(course.value.id), courseName: course.value.courseName, courseType: course.value.courseType, updatedAt: course.value.updatedAt } : null)
const loading = ref(false)
const loadError = ref('')
const mutating = ref(false)
const publishing = ref(false)
const knowledgeState = ref({ dirty: false, busy: false })
const knowledgePanel = ref<InstanceType<typeof CourseKnowledgePanel> | null>(null)
const chapterEditor = ref(false)
const editingChapter = ref<ChapterVO | null>(null)
const form = reactive({ title: '' })
const baseline = ref('')
const formError = ref('')
const deletingChapter = ref<ChapterVO | null>(null)
const publishOpen = ref(false)
const dragIndex = ref<number | null>(null)
const dropIndex = ref<number | null>(null)
const busy = computed(() => mutating.value || publishing.value || knowledgeState.value.busy)
const dirty = computed(() => knowledgeState.value.dirty || (chapterEditor.value && form.title !== baseline.value))
const canReorder = computed(() => course.value?.courseType === 'PRIVATE' && !busy.value && !dirty.value && !chapterEditor.value)
let generation = 0
let disposed = false
const SORT_STEP = 1000
const MAX_SORT_ORDER = 4_294_967_295

function errorMessage(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : '操作失败，请稍后重试'
}

// 课程、账号和页面代次共同决定请求归属，旧页面响应不能覆盖新位置。
function snapshot() { return { generation, id: courseId.value, token: currentUser.value?.token } }
function isCurrent(state: ReturnType<typeof snapshot>) {
  return !disposed && state.generation === generation && state.id === courseId.value && state.token === currentUser.value?.token
}

function discardEdits() {
  knowledgePanel.value?.discardEdits()
  chapterEditor.value = false
  deletingChapter.value = null
  publishOpen.value = false
  form.title = baseline.value
}

// 保存期间阻止离开；仅在确有未保存输入时询问是否丢弃。
function allowNavigation() {
  // 登录凭证已失效时直接退出编辑区，不能用未保存提示挡住重新登录。
  if (!getStoredToken()) { discardEdits(); return true }
  if (busy.value) { showToast('info', '正在保存', '请等待当前操作完成'); return false }
  if (dirty.value && !window.confirm('有未保存的修改，确定放弃并离开当前编辑位置吗？')) return false
  discardEdits()
  return true
}
onBeforeRouteLeave(allowNavigation)
onBeforeRouteUpdate((to, from) => to.fullPath === from.fullPath || allowNavigation())

function beforeUnload(event: BeforeUnloadEvent) {
  if (!getStoredToken()) return
  if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { disposed = true; generation++; window.removeEventListener('beforeunload', beforeUnload) })

async function loadEditor() {
  if (busy.value || dirty.value) return
  const state = snapshot()
  loading.value = true
  loadError.value = ''
  try {
    // 先确认课程归属，成功后才查询章节；课程详情是刷新和直链的依据。
    const detail = await getCourse(state.id)
    if (!isCurrent(state)) return
    course.value = detail
    upsertCourse(detail, state.token)
    const result = await getChaptersByCourseId(state.id)
    if (!isCurrent(state)) return
    chapters.value = result.sort((a, b) => a.sortOrder - b.sortOrder)
    syncSelectedChapter()
  } catch (cause) {
    if (isCurrent(state)) loadError.value = errorMessage(cause)
  } finally { if (isCurrent(state)) loading.value = false }
}

function syncSelectedChapter() {
  const requested = typeof route.query.chapter === 'string' ? route.query.chapter : selectedId.value
  selectedId.value = chapters.value.find(chapter => chapter.id === requested)?.id ?? chapters.value[0]?.id ?? ''
  // URL 保存当前位置，刷新或分享直链都能继续同一章。
  if (route.query.chapter !== (selectedId.value || undefined)) {
    void router.replace({ query: { ...route.query, chapter: selectedId.value || undefined } })
  }
}

function selectChapter(chapter: ChapterVO) {
  if (chapter.id === selectedId.value) return
  void router.push({ query: { ...route.query, chapter: chapter.id } })
}

function openChapterEditor(chapter: ChapterVO | null = null) {
  if (busy.value || !allowNavigation()) return
  editingChapter.value = chapter ? { ...chapter } : null
  baseline.value = chapter?.title ?? ''
  form.title = baseline.value
  formError.value = ''
  chapterEditor.value = true
}

function closeChapterEditor() {
  if (mutating.value) return
  if (form.title !== baseline.value && !window.confirm('章节有未保存的修改，确定放弃吗？')) return
  chapterEditor.value = false
  form.title = baseline.value
}

async function saveChapter() {
  if (busy.value || !chapterEditor.value) return
  const title = form.title.trim()
  formError.value = !title ? '请输入章节标题' : title.length > 255 ? '章节标题不能超过 255 个字符' : ''
  if (formError.value) return
  const state = snapshot()
  const target = editingChapter.value
  const sortOrder = target?.sortOrder ?? ((chapters.value[chapters.value.length - 1]?.sortOrder ?? 0) + SORT_STEP)
  if (sortOrder > MAX_SORT_ORDER) { formError.value = '章节顺序暂时无法继续扩展'; return }
  mutating.value = true
  try {
    const payload = { courseId: state.id, title, sortOrder, ...(target ? { id: target.id } : {}) }
    const result = target ? await updateChapters([payload]) : await createChapters([payload])
    if (!isCurrent(state)) return
    const saved = result[0]
    if (!saved) throw new Error('服务器没有返回保存后的章节，请刷新后确认')
    chapters.value = target ? chapters.value.map(item => item.id === target.id ? saved : item) : [...chapters.value, saved]
    chapters.value.sort((a, b) => a.sortOrder - b.sortOrder)
    chapterEditor.value = false
    if (!target) selectedId.value = saved.id
    showToast('success', target ? '章节已更新' : '章节已添加', title)
  } catch (cause) { if (isCurrent(state)) formError.value = errorMessage(cause) }
  finally { if (isCurrent(state)) mutating.value = false }
  if (isCurrent(state) && !target && !chapterEditor.value) {
    void router.replace({ query: { ...route.query, chapter: selectedId.value } })
  }
}

function openDeleteChapter(chapter: ChapterVO) {
  if (!allowNavigation()) return
  deletingChapter.value = { ...chapter }
}

async function deleteChapter() {
  if (busy.value || !deletingChapter.value) return
  const state = snapshot()
  const target = deletingChapter.value
  mutating.value = true
  try {
    await deleteChaptersByIds([target.id])
    if (!isCurrent(state)) return
    chapters.value = chapters.value.filter(item => item.id !== target.id)
    deletingChapter.value = null
    showToast('success', '章节已删除', target.title)
  } catch (cause) { if (isCurrent(state)) showToast('error', '删除章节失败', errorMessage(cause)) }
  finally { if (isCurrent(state)) mutating.value = false }
  if (isCurrent(state)) syncSelectedChapter()
}

// 在相邻顺序号之间取值，只更新移动章节；保存失败恢复整个目录的原顺序。
async function reorderChapter(from: number, to: number) {
  if (!canReorder.value || from === to || to < 0 || to >= chapters.value.length) return
  const state = snapshot()
  const original = chapters.value.map(item => ({ ...item }))
  const reordered = original.map(item => ({ ...item }))
  const [moved] = reordered.splice(from, 1)
  if (!moved) return
  reordered.splice(to, 0, moved)
  const previous = reordered[to - 1]
  const next = reordered[to + 1]
  const order = previous && next ? (next.sortOrder - previous.sortOrder > 1 ? previous.sortOrder + Math.floor((next.sortOrder - previous.sortOrder) / 2) : null)
    : previous ? previous.sortOrder + SORT_STEP : next ? (next.sortOrder > 0 ? Math.floor(next.sortOrder / 2) : null) : SORT_STEP
  if (order === null || order > MAX_SORT_ORDER) { showToast('error', '暂时无法调整到该位置', '相邻章节顺序号没有可用间隔'); return }
  reordered[to] = { ...moved, sortOrder: order }
  chapters.value = reordered
  mutating.value = true
  try {
    const result = await updateChapters([{ ...reordered[to]!, courseId: state.id }])
    if (!isCurrent(state)) return
    if (result[0]) reordered[to] = result[0]
    chapters.value = reordered.sort((a, b) => a.sortOrder - b.sortOrder)
    showToast('success', '章节顺序已保存', moved.title)
  } catch (cause) {
    if (isCurrent(state)) { chapters.value = original; showToast('error', '排序失败，已恢复原顺序', errorMessage(cause)) }
  } finally { if (isCurrent(state)) mutating.value = false }
}

function startDrag(event: DragEvent, index: number) {
  if (!canReorder.value || (event.target as HTMLElement)?.closest('button')) { event.preventDefault(); return }
  dragIndex.value = index
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', String(index)) }
}
function endDrag() { dragIndex.value = null; dropIndex.value = null }
function dropChapter(index: number) {
  const from = dragIndex.value
  endDrag()
  if (from !== null) void reorderChapter(from, index)
}

async function submitPublish() {
  if (busy.value || dirty.value || course.value?.courseType !== 'PRIVATE') return
  const state = snapshot()
  publishing.value = true
  try {
    const result = await publishCourse(state.id)
    if (!isCurrent(state)) return
    course.value = result
    upsertCourse(result, state.token)
    addActivity({ kind: 'course-published', title: result.courseName, description: '已提交审核', status: result.courseType, resourceId: state.id })
    publishOpen.value = false
    showToast('success', '已提交审核', result.courseName)
  } catch (cause) { if (isCurrent(state)) showToast('error', '提交审核失败', errorMessage(cause)) }
  finally { if (isCurrent(state)) publishing.value = false }
}

watch(() => [courseId.value, currentUser.value?.token], () => {
  generation++
  mutating.value = false
  publishing.value = false
  knowledgeState.value = { dirty: false, busy: false }
  discardEdits()
  course.value = null
  chapters.value = []
  selectedId.value = ''
  void loadEditor()
}, { immediate: true })
watch(() => route.query.chapter, () => {
  if (!chapters.value.length) return
  syncSelectedChapter()
})
</script>

<template>
  <div class="course-editor-view">
    <header class="course-editor-heading">
      <div class="course-editor-title"><button class="icon-button" title="返回课程列表" aria-label="返回课程列表" @click="router.push({ name: 'courses' })"><ChevronLeft :size="21" /></button><div><span class="section-kicker">课程编辑</span><h2>{{ course?.courseName || '课程' }}</h2><p v-if="selectedChapter">{{ course?.courseName }} / {{ selectedChapter.title }}</p></div></div>
      <div class="course-editor-actions"><StatusBadge v-if="course" :status="course.courseType" /><button v-if="course?.courseType === 'PRIVATE'" class="button button-primary" :disabled="busy || dirty || loading || Boolean(loadError) || chapterEditor" @click="publishOpen = true"><Send :size="16" /> 提交审核</button></div>
    </header>
    <div v-if="loading" class="knowledge-loading" role="status"><RefreshCw :size="24" class="spin" /> 正在加载课程…</div>
    <div v-else-if="loadError" class="chapter-load-error"><CircleAlert :size="24" /><div><strong>无法打开课程编辑</strong><p>{{ loadError }}</p></div><button class="button button-secondary" @click="loadEditor">重试</button></div>
    <div v-else-if="knownCourse" class="course-editor-layout">
      <aside class="course-chapter-outline" aria-label="章节目录">
        <header><h3><ListTree :size="19" /> 章节 <small>{{ chapters.length }}</small></h3><button class="icon-button" :disabled="busy" title="添加章节" aria-label="添加章节" @click="openChapterEditor()"><Plus :size="19" /></button></header>
        <div v-if="chapters.length" class="course-chapter-list">
          <article v-for="(chapter, index) in chapters" :key="chapter.id" :class="{ selected: chapter.id === selectedId, 'drop-target': dropIndex === index }" :draggable="canReorder" @dragstart="startDrag($event, index)" @dragend="endDrag" @dragover.prevent="dropIndex = index" @drop.prevent="dropChapter(index)">
            <span class="course-chapter-grip"><GripVertical :size="15" /></span><button class="course-chapter-select" :disabled="busy" :aria-current="chapter.id === selectedId ? 'true' : undefined" @click="selectChapter(chapter)"><small>第 {{ index + 1 }} 章</small><strong>{{ chapter.title }}</strong></button>
            <div class="course-chapter-tools"><button class="icon-button" :disabled="busy" :aria-label="`修改章节 ${chapter.title}`" title="修改章节" @click="openChapterEditor(chapter)"><Pencil :size="14" /></button><button class="icon-button" :disabled="busy" :aria-label="`删除章节 ${chapter.title}`" title="删除章节" @click="openDeleteChapter(chapter)"><Trash2 :size="14" /></button><button class="icon-button" :disabled="!canReorder || index === 0" :aria-label="`上移章节 ${chapter.title}`" title="上移章节" @click="reorderChapter(index, index - 1)"><ArrowUp :size="14" /></button><button class="icon-button" :disabled="!canReorder || index === chapters.length - 1" :aria-label="`下移章节 ${chapter.title}`" title="下移章节" @click="reorderChapter(index, index + 1)"><ArrowDown :size="14" /></button></div>
          </article>
        </div>
        <p v-else class="course-outline-empty">暂无章节</p>
      </aside>
      <CourseKnowledgePanel v-if="selectedChapter" ref="knowledgePanel" :course="knownCourse" :chapter="selectedChapter" :blocked="mutating || publishing || chapterEditor || publishOpen || Boolean(deletingChapter)" @state-change="knowledgeState = $event" />
      <EmptyState v-else title="这门课程还没有章节" description="" />
    </div>
    <ModalDialog :open="chapterEditor" :title="editingChapter ? '修改章节' : '添加章节'" @close="closeChapterEditor"><form class="form-layout" @submit.prevent="saveChapter"><div class="form-section"><label class="field-label" for="chapter-title">章节标题 <b>*</b></label><input id="chapter-title" v-model="form.title" class="form-input" maxlength="255" :disabled="mutating"><span v-if="formError" class="field-error">{{ formError }}</span></div><footer class="form-actions"><button type="button" class="button button-secondary" :disabled="mutating" @click="closeChapterEditor">取消</button><button class="button button-primary" :disabled="mutating">{{ mutating ? '保存中…' : '保存章节' }}</button></footer></form></ModalDialog>
    <ModalDialog :open="Boolean(deletingChapter)" title="删除章节" :description="`确认删除“${deletingChapter?.title ?? ''}”？`" @close="!mutating && (deletingChapter = null)"><p>该章节及其知识点会一并删除，删除后无法恢复。</p><footer class="form-actions"><button class="button button-secondary" :disabled="mutating" @click="deletingChapter = null">取消</button><button class="button chapter-delete-button" :disabled="busy" @click="deleteChapter">{{ mutating ? '删除中…' : '确认删除' }}</button></footer></ModalDialog>
    <ModalDialog :open="publishOpen" title="提交课程审核" :description="course?.courseName" @close="!publishing && (publishOpen = false)"><p>提交后，课程进入待审核状态，章节和知识点的顺序暂不可调整。</p><footer class="form-actions"><button class="button button-secondary" :disabled="publishing" @click="publishOpen = false">取消</button><button class="button button-primary" :disabled="busy || dirty" @click="submitPublish">{{ publishing ? '提交中…' : '确认提交' }}</button></footer></ModalDialog>
  </div>
</template>

<style scoped>
.course-editor-heading, .course-editor-title, .course-editor-actions { display: flex; align-items: center; gap: 12px; }
.course-editor-heading { justify-content: space-between; padding-bottom: 22px; border-bottom: 1px solid var(--line); }
.course-editor-title { min-width: 0; }
.course-editor-title > div { min-width: 0; }
.course-editor-title h2 { margin: 4px 0; font-size: 24px; overflow-wrap: anywhere; }
.course-editor-title p { margin: 6px 0 0; color: var(--muted); font-size: 13px; overflow-wrap: anywhere; }
.course-editor-actions { flex-shrink: 0; }
.course-editor-layout { display: grid; grid-template-columns: 280px minmax(0, 1fr); margin-top: 22px; align-items: start; }
.course-chapter-outline { border-right: 1px solid var(--line); padding-right: 20px; min-width: 0; }
.course-chapter-outline > header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.course-chapter-outline h3 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 15px; }
.course-chapter-outline h3 small { color: var(--muted); font-weight: 400; }
.course-chapter-list { display: grid; gap: 8px; }
.course-chapter-list > article { display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 5px; align-items: center; padding: 10px; border: 1px solid transparent; border-radius: 6px; }
.course-chapter-list > article.selected { background: #eaf6f2; border-color: #b6dbd0; }
.course-chapter-list > article.drop-target { border-color: #218f83; }
.course-chapter-grip { color: var(--muted); }
.course-chapter-select { text-align: left; border: 0; background: transparent; cursor: pointer; min-width: 0; color: var(--ink); padding: 0; }
.course-chapter-select small { display: block; font-size: 11px; color: var(--muted); margin-bottom: 4px; }
.course-chapter-select strong { font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; display: block; }
.course-chapter-tools { grid-column: 2; display: flex; gap: 2px; margin-top: 5px; }
.course-chapter-tools .icon-button { width: 27px; height: 27px; min-height: 27px; }
.course-outline-empty { color: var(--muted); font-size: 13px; }
:deep(.course-knowledge-panel) { min-width: 0; padding-left: 24px; }
:deep(.knowledge-workspace-header) { flex-wrap: wrap; gap: 12px; }
:deep(.knowledge-workspace-identity) { min-width: 0; }
:deep(.knowledge-workspace-identity > div) { min-width: 0; }
:deep(.knowledge-workspace-identity h3), :deep(.knowledge-point-copy h4), :deep(.knowledge-point-copy p), :deep(.relation-result-copy strong) { overflow-wrap: anywhere; white-space: normal; }
:deep(.knowledge-workspace-actions) { flex-wrap: wrap; }
:deep(.course-relation-panel) { margin-top: 24px; }
:deep(.course-editor-fields) { display: grid; gap: 18px; border: 0; margin: 0; padding: 0; min-width: 0; }
@media (max-width: 1100px) { .course-editor-layout { grid-template-columns: 230px minmax(0, 1fr); } }
@media (max-width: 760px) {
  .course-editor-heading { align-items: flex-start; flex-direction: column; }
  .course-editor-actions { width: 100%; justify-content: space-between; }
  .course-editor-title h2 { font-size: 21px; }
  .course-editor-layout { grid-template-columns: minmax(0, 1fr); }
  .course-chapter-outline { padding: 0 0 18px; border-right: 0; border-bottom: 1px solid var(--line); margin-bottom: 20px; }
  .course-chapter-list { max-height: 300px; overflow-y: auto; }
  :deep(.course-knowledge-panel) { padding-left: 0; }
}
</style>
