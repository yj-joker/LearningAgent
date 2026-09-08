<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { BookOpenText, CheckCircle2, Download, FileText, FolderOpen, LockKeyhole, Plus, RefreshCw, Upload, X } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { createKnowledgeBase } from '@/api/knowledgeBase'
import { downloadDocument, uploadDocument } from '@/api/documents'
import { useActivity } from '@/composables/useActivity'
import { useToast } from '@/composables/useToast'
import type { DocumentStatus, DocumentVO, KnowledgeBaseVO } from '@/types/api'

interface LocalKnowledgeBase extends KnowledgeBaseVO {
  courseId: string
  courseName: string
  documents: DocumentVO[]
}

const { knownCourses } = useActivity()
const { showToast } = useToast()
const selectedCourseId = ref('')
const bases = ref<LocalKnowledgeBase[]>([])
const createOpen = ref(false)
const creating = ref(false)
const uploadingId = ref<string | null>(null)
const downloadingId = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const form = reactive({ name: '', description: '' })
const errors = reactive({ name: '' })

const selectedCourse = computed(() => knownCourses.value.find((course) => course.courseId === selectedCourseId.value) ?? null)
const selectedBases = computed(() => bases.value.filter((base) => base.courseId === selectedCourseId.value))

watch(knownCourses, (courses) => {
  const firstCourse = courses[0]
  if (!selectedCourseId.value && firstCourse) selectedCourseId.value = firstCourse.courseId
  if (selectedCourseId.value && !courses.some((course) => course.courseId === selectedCourseId.value)) selectedCourseId.value = courses[0]?.courseId ?? ''
}, { immediate: true })

const statusLabels: Record<DocumentStatus, string> = {
  UPLOADED: '已上传',
  PARSING: '解析中',
  READY: '可使用',
  FAILED: '解析失败',
}

function formatSize(size: number) {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function openCreate() {
  if (!selectedCourse.value) {
    showToast('info', '先选择课程', '请选择一门课程后再创建知识库')
    return
  }
  form.name = ''
  form.description = ''
  errors.name = ''
  createOpen.value = true
}

function validateCreate() {
  errors.name = form.name.trim() ? '' : '请输入知识库名称'
  return !errors.name
}

async function submitCreate() {
  if (!validateCreate() || !selectedCourse.value) return
  creating.value = true
  try {
    const result = await createKnowledgeBase({
      courseId: selectedCourse.value.courseId,
      name: form.name.trim(),
      description: form.description.trim() || null,
    })
    bases.value.unshift({
      ...result,
      courseId: selectedCourse.value.courseId,
      courseName: selectedCourse.value.courseName,
      documents: [],
    })
    createOpen.value = false
    showToast('success', '知识库已创建', `${result.name} 已绑定到 ${selectedCourse.value.courseName}`)
  } catch (error) {
    showToast('error', '创建失败', error instanceof ApiError ? error.message : '知识库创建失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  input.value = ''
}

function clearFile() {
  selectedFile.value = null
}

async function submitUpload(base: LocalKnowledgeBase) {
  if (!selectedFile.value || uploadingId.value) return
  if (selectedCourse.value?.courseType !== 'PRIVATE') {
    showToast('info', '课程暂不可上传', '只有私有课程的用户知识库允许上传文件')
    return
  }
  const file = selectedFile.value
  uploadingId.value = String(base.id)
  try {
    const result = await uploadDocument(base.id, file)
    base.documents.unshift(result)
    showToast('success', '文件上传成功', file.name)
    clearFile()
  } catch (error) {
    showToast('error', '上传失败', error instanceof ApiError ? error.message : '文件上传失败，请稍后重试')
  } finally {
    uploadingId.value = null
  }
}

async function download(item: DocumentVO) {
  if (downloadingId.value) return
  downloadingId.value = String(item.id)
  try {
    await downloadDocument(item.id, item.filename)
  } catch (error) {
    showToast('error', '下载失败', error instanceof ApiError ? error.message : '文件下载失败，请稍后重试')
  } finally {
    downloadingId.value = null
  }
}
</script>

<template>
  <div class="resource-view knowledge-base-view">
    <section class="page-heading">
      <div><span class="section-kicker">资料中心</span><h2>知识库</h2><p>为课程建立资料空间，上传文档并沉淀可复用的学习内容。</p></div>
      <button class="button button-primary" :disabled="!selectedCourse" @click="openCreate"><Plus :size="18" /> 创建知识库</button>
    </section>

    <section class="knowledge-base-layout">
      <aside class="panel knowledge-base-course-panel">
        <div class="panel-header"><div><span class="section-kicker">第一步</span><h3>选择课程</h3></div><BookOpenText :size="19" /></div>
        <div v-if="knownCourses.length" class="knowledge-base-course-list">
          <button v-for="course in knownCourses" :key="course.courseId" :class="{ active: selectedCourseId === course.courseId }" @click="selectedCourseId = course.courseId">
            <span class="knowledge-base-course-icon"><BookOpenText :size="17" /></span>
            <span><strong>{{ course.courseName }}</strong><small><StatusBadge :status="course.courseType" /></small></span>
          </button>
        </div>
        <EmptyState v-else title="还没有课程" description="先创建课程，再建立对应的知识库。" />
      </aside>

      <section class="panel knowledge-base-content-panel">
        <div v-if="selectedCourse" class="panel-header">
          <div><span class="section-kicker">第二步</span><h3>{{ selectedCourse.courseName }} 的知识库</h3></div>
          <span class="count-pill">{{ selectedBases.length }} 个</span>
        </div>
        <EmptyState v-else title="选择一门课程开始" description="知识库会按课程分别管理，选择左侧课程后继续。" />

        <template v-if="selectedCourse">
          <div class="knowledge-base-session-note"><RefreshCw :size="14" /><span>当前后端尚未提供知识库和文档列表查询；这里展示的是本次打开页面后创建、上传的内容。</span></div>
          <div v-if="!selectedBases.length" class="knowledge-base-empty-tip"><FolderOpen :size="24" /><strong>还没有知识库</strong><p>创建一个资料空间，然后上传课程文档。</p><button class="button button-secondary" @click="openCreate"><Plus :size="16" /> 创建第一个知识库</button></div>
          <div v-else class="knowledge-base-cards">
            <article v-for="base in selectedBases" :key="String(base.id)" class="knowledge-base-card">
              <header><span class="knowledge-base-icon"><FolderOpen :size="20" /></span><div><h4>{{ base.name }}</h4><p>{{ base.description || '暂无描述' }}</p></div><span class="knowledge-base-visibility"><LockKeyhole :size="13" /> {{ base.visibility === 'PRIVATE' ? '私有' : '公开' }}</span></header>
              <div class="knowledge-base-upload" :class="{ disabled: selectedCourse.courseType !== 'PRIVATE' }">
                <label class="knowledge-base-file-picker">
                  <input type="file" :disabled="selectedCourse.courseType !== 'PRIVATE'" @change="chooseFile">
                  <FileText :size="19" /><span>{{ selectedFile?.name || '选择一个文档上传' }}</span><b>{{ selectedFile ? '重新选择' : '浏览文件' }}</b>
                </label>
                <button v-if="selectedFile" class="icon-button" aria-label="移除文件" @click="clearFile"><X :size="15" /></button>
                <button class="button button-secondary" :disabled="!selectedFile || Boolean(uploadingId) || selectedCourse.courseType !== 'PRIVATE'" @click="submitUpload(base)"><Upload :size="15" /> {{ uploadingId === String(base.id) ? '上传中…' : '上传' }}</button>
              </div>
              <p v-if="selectedCourse.courseType !== 'PRIVATE'" class="knowledge-base-lock-tip"><LockKeyhole :size="13" /> 课程进入审核或发布状态后，暂不可上传用户知识库文件。</p>
              <div v-if="base.documents.length" class="document-list">
                <div v-for="item in base.documents" :key="String(item.id)" class="document-row"><span class="document-row-icon"><FileText :size="16" /></span><div><strong>{{ item.filename }}</strong><small>{{ formatSize(item.fileSize) }} · {{ statusLabels[item.status] }}</small></div><button class="icon-button" :disabled="downloadingId === String(item.id)" title="下载文档" @click="download(item)"><RefreshCw v-if="downloadingId === String(item.id)" :size="15" class="spin" /><Download v-else :size="16" /></button></div>
              </div>
              <p v-else class="document-empty">上传后的文档会显示在这里。</p>
            </article>
          </div>
        </template>
      </section>
    </section>

    <ModalDialog :open="createOpen" title="创建知识库" description="填写知识库名称和用途说明。" @close="createOpen = false">
      <form class="form-layout" @submit.prevent="submitCreate">
        <div class="form-section"><label class="field-label" for="knowledge-base-name">知识库名称 <b>*</b></label><input id="knowledge-base-name" v-model="form.name" class="form-input" maxlength="255" placeholder="例如：Java 并发课程资料" autofocus @input="errors.name = ''"><span v-if="errors.name" class="field-error">{{ errors.name }}</span></div>
        <div class="form-section"><label class="field-label" for="knowledge-base-description">用途说明</label><textarea id="knowledge-base-description" v-model="form.description" class="form-textarea" rows="4" maxlength="1000" placeholder="说明这里准备收录哪些学习资料。" /></div>
        <div class="knowledge-base-modal-note"><CheckCircle2 :size="17" /><span>用户创建的知识库默认为私有，仅绑定课程的拥有者可以上传资料。</span></div>
        <footer class="form-actions"><button type="button" class="button button-secondary" @click="createOpen = false">取消</button><button class="button button-primary" :disabled="creating">{{ creating ? '创建中…' : '创建知识库' }} <Plus v-if="!creating" :size="16" /></button></footer>
      </form>
    </ModalDialog>
  </div>
</template>
