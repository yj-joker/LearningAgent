<script setup lang="ts">
import { computed, ref } from 'vue'
import { Braces, Check, ChevronDown, Copy, ExternalLink, Info, ServerCog } from 'lucide-vue-next'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'

interface Endpoint {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  path: string
  title: string
  description: string
  body?: string
  response: string
  notes: string[]
  adminOnly?: boolean
}

const { showToast } = useToast()
const { isAdmin } = useAuth()
const openPath = ref<string | null>('/learning-agent/user/register')
const filter = ref<'ALL' | 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'>('ALL')

const endpoints: Endpoint[] = [
  {
    method: 'GET',
    path: '/learning-agent/user/pageQuery',
    title: '管理员分页查询用户',
    description: '管理员查看全部用户，并按用户名、角色和创建时间范围筛选。',
    adminOnly: true,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "page": 1,
    "size": 20,
    "totalElements": 42,
    "totalPages": 3,
    "hasPrevious": false,
    "hasNext": true,
    "items": [{
      "id": "1987654321098765432",
      "username": "demo-learner",
      "role": "USER",
      "createdAt": "2026-07-28T10:00:00"
    }]
  }
}`,
    notes: ['需要 ADMIN 角色和 Bearer Token', 'username 使用模糊匹配', 'page 从 1 开始，size 最大为 100', '用户 ID 必须作为字符串处理'],
  },
  {
    method: 'POST',
    path: '/learning-agent/user/register',
    title: '用户注册',
    description: '创建一个学习账号。注册成功响应不包含 JWT，需要随后登录。',
    body: `{
  "username": "demo-learner",
  "password": "your-password"
}`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "username": "demo-learner",
    "avatarUrl": null,
    "token": null
  }
}`,
    notes: ['username 和 password 必填', '用户名重复时返回业务错误', '注册成功后需要调用登录接口获取 token'],
  },
  {
    method: 'POST',
    path: '/learning-agent/user/login',
    title: '用户登录',
    description: '校验用户名和密码，并返回后续业务请求使用的 JWT。',
    body: `{
  "username": "demo-learner",
  "password": "your-password"
}`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "username": "demo-learner",
    "avatarUrl": null,
    "token": "eyJhbGciOiJIUzI1NiJ9..."
  }
}`,
    notes: ['登录成功后前端会保存 token', '业务请求自动携带 Authorization: Bearer <token>', '用户名或密码错误时后端返回业务错误'],
  },
  {
    method: 'POST',
    path: '/learning-agent/courses/createCourse',
    title: '创建课程',
    description: '创建一门私有课程。课程必须先提交管理员审核，审核通过后才会公开。',
    body: `{
  "courseName": "Java 并发编程",
  "difficultyLevel": 4,
  "learningOutline": "{\\"content\\":\\"线程与锁\\"}"
}`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "id": 1001,
    "courseName": "Java 并发编程",
    "publisherId": 2001,
    "difficultyLevel": 4,
    "learningOutline": "{...}",
    "courseType": "PRIVATE",
    "createdAt": "2026-07-28T16:00:00",
    "updatedAt": "2026-07-28T16:00:00"
  }
}`,
    notes: ['新课程的 courseType 固定为 PRIVATE，客户端不能指定', 'difficultyLevel 的数据库约束为 1～5', '响应包含后续提交审核需要的 id'],
  },
  {
    method: 'PATCH',
    path: '/learning-agent/courses/publishCourse/{courseId}',
    title: '提交课程审核',
    description: '将当前用户拥有的 PRIVATE 课程提交为 PENDING，等待管理员审核。',
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "courseName": "Java 并发编程",
    "courseType": "PENDING"
  }
}`,
    notes: ['courseId 通过路径参数传递', '课程必须存在且属于当前用户', '只有 PRIVATE 状态可以提交审核'],
  },
  {
    method: 'POST',
    path: '/createChapters',
    title: '批量添加章节',
    description: '向同一门课程添加一个或多个章节，标题和排序值在课程内必须唯一。',
    body: `[
  {
    "title": "线程与并发基础",
    "courseId": 1001,
    "sortOrder": 1000
  }
]`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": [{
    "id": 1990000000000000001,
    "title": "线程与并发基础",
    "sortOrder": 1000
  }]
}`,
    notes: ['请求体必须是数组', '同一批章节必须属于同一课程', '章节 ID 是雪花 ID，前端按字符串保存', '课程所有者才可以添加章节'],
  },
  {
    method: 'GET',
    path: '/getChaptersByCourseId/{courseId}',
    title: '查询课程章节',
    description: '获取指定课程的章节列表，后端按照 sort_order 升序返回。',
    response: `{
  "code": "200",
  "message": "OK",
  "data": [
    { "id": 1990000000000000001, "title": "基础", "sortOrder": 1000 },
    { "id": 1990000000000000002, "title": "进阶", "sortOrder": 2000 }
  ]
}`,
    notes: ['课程所有者才可以查询', '返回数据不包含 courseId 和课程状态', '前端不能据此判断课程是否为 PRIVATE'],
  },
  {
    method: 'PUT',
    path: '/updateChapters',
    title: '批量修改章节',
    description: '修改同一门课程中的章节标题或 sortOrder，拖拽排序也使用该接口。',
    body: `[
  {
    "id": 1990000000000000002,
    "title": "进阶",
    "id": 1001,
    "sortOrder": 1500
  }
]`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": [{
    "id": 1990000000000000002,
    "title": "进阶",
    "sortOrder": 1500
  }]
}`,
    notes: ['请求体必须是数组', 'id、title、courseId、sortOrder 都需要传递', '排序位置冲突时应刷新列表后重试'],
  },
  {
    method: 'DELETE',
    path: '/deleteChaptersByIds/{ids}',
    title: '批量删除章节',
    description: '根据逗号分隔的章节 ID 删除同一课程中的一个或多个章节。',
    response: `{
  "code": "200",
  "message": "OK",
  "data": null
}`,
    notes: ['示例路径：/deleteChaptersByIds/101,102', '章节必须存在且属于当前用户的同一课程', '当前后端尚未实现章节下知识点的级联删除'],
  },
  {
    method: 'PATCH',
    path: '/learning-agent/courses/passCourse/{courseId}',
    title: '管理员审核通过课程',
    description: '管理员将 PENDING 课程更新为 PUBLISHED；只有 PUBLISHED 课程可被其他学习者访问。',
    adminOnly: true,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "id": 1001,
    "courseType": "PUBLISHED"
  }
}`,
    notes: ['需要 ADMIN 角色', '只有 PENDING 状态可以审核通过'],
  },
  {
    method: 'PATCH',
    path: '/learning-agent/courses/rejectCourse/{courseId}',
    title: '管理员驳回或下架课程',
    description: '管理员可将 PENDING 课程驳回，或将 PUBLISHED 课程下架，目标状态均为 PRIVATE。',
    adminOnly: true,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "id": 1001,
    "courseType": "PRIVATE"
  }
}`,
    notes: ['需要 ADMIN 角色', '只允许 PENDING → PRIVATE 或 PUBLISHED → PRIVATE'],
  },
  {
    method: 'POST',
    path: '/learning-agent/learning/session',
    title: '创建学习会话',
    description: '为自己拥有的课程或已发布课程创建一个 ACTIVE 学习会话。',
    body: `{
  "courseId": 1001,
  "sessionTitle": "理解线程池核心参数"
}`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "sessionTitle": "理解线程池核心参数",
    "sessionStatus": "ACTIVE"
  }
}`,
    notes: ['courseId 和 sessionTitle 必填', '请求中的 status 会被后端忽略', '当前响应不包含学习会话 ID'],
  },
  {
    method: 'PUT',
    path: '/learning-agent/learning/session/completed/{learningSessionId}',
    title: '完成学习会话',
    description: '把属于当前用户的 ACTIVE 会话更新为 COMPLETED。',
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "sessionTitle": "理解线程池核心参数",
    "sessionStatus": "COMPLETED"
  }
}`,
    notes: ['learningSessionId 通过路径参数传递', '只有 ACTIVE 状态可以完成', '已完成会话不能重复完成'],
  },
]

const availableEndpoints = computed(() => endpoints.filter((item) => !item.adminOnly || isAdmin.value))
const visibleEndpoints = computed(() => filter.value === 'ALL'
  ? availableEndpoints.value
  : availableEndpoints.value.filter((item) => item.method === filter.value))

async function copyText(value: string) {
  await navigator.clipboard.writeText(value)
  showToast('success', '已复制', '内容已复制到剪贴板')
}
</script>

<template>
  <div class="api-view">
    <section class="page-heading">
      <div>
        <span class="section-kicker">API REFERENCE</span>
        <h2>前后端接口契约</h2>
        <p>根据当前登录角色展示可调用的接口，你现在可以使用 {{ availableEndpoints.length }} 个业务接口。</p>
      </div>
      <a class="button button-secondary" href="http://localhost:8080/swagger-ui/index.html" target="_blank" rel="noreferrer">
        打开 Swagger <ExternalLink :size="16" />
      </a>
    </section>

    <section class="api-summary">
      <div><span><ServerCog :size="22" /></span><strong>http://localhost:8080</strong><small>开发环境后端地址</small></div>
      <div><span><Braces :size="22" /></span><strong>Result&lt;T&gt;</strong><small>统一响应结构</small></div>
      <div><span><Check :size="22" /></span><strong>code === "200"</strong><small>业务成功判断</small></div>
    </section>

    <div class="api-layout">
      <section class="panel endpoints-panel">
        <div class="panel-header api-panel-header">
          <div><span class="section-kicker">ENDPOINTS</span><h3>当前角色可用接口</h3></div>
          <div class="filter-tabs">
            <button v-for="value in ['ALL', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const" :key="value" :class="{ active: filter === value }" @click="filter = value">
              {{ value === 'ALL' ? '全部' : value }}
            </button>
          </div>
        </div>
        <div class="endpoint-list">
          <article v-for="endpoint in visibleEndpoints" :key="endpoint.path" class="endpoint-item" :class="{ open: openPath === endpoint.path }">
            <button class="endpoint-trigger" @click="openPath = openPath === endpoint.path ? null : endpoint.path">
              <span class="method-tag" :class="`method-${endpoint.method.toLowerCase()}`">{{ endpoint.method }}</span>
              <span class="endpoint-main"><strong>{{ endpoint.title }}</strong><code>{{ endpoint.path }}</code></span>
              <ChevronDown :size="19" />
            </button>
            <div v-if="openPath === endpoint.path" class="endpoint-content">
              <p>{{ endpoint.description }}</p>
              <div v-if="endpoint.body" class="code-section">
                <div><strong>请求体</strong><button @click="copyText(endpoint.body!)"><Copy :size="14" /> 复制</button></div>
                <pre><code>{{ endpoint.body }}</code></pre>
              </div>
              <div class="code-section">
                <div><strong>成功响应示例</strong><button @click="copyText(endpoint.response)"><Copy :size="14" /> 复制</button></div>
                <pre><code>{{ endpoint.response }}</code></pre>
              </div>
              <ul class="note-list">
                <li v-for="note in endpoint.notes" :key="note"><Check :size="14" />{{ note }}</li>
              </ul>
            </div>
          </article>
        </div>
      </section>

      <aside class="side-stack api-aside">
        <section class="panel response-shape">
          <span class="side-icon"><Braces :size="20" /></span>
          <h3>统一响应结构</h3>
          <pre><code>{
  "code": "200",
  "message": "OK",
  "data": { ... }
}</code></pre>
          <p>注意：后端业务失败通常仍返回 HTTP 200，前端必须继续检查响应体中的 <code>code</code>。</p>
        </section>
        <section class="insight-card warning-card">
          <Info :size="20" />
          <div><strong>接口限制</strong><p>当前没有课程详情和会话查询接口；章节接口也不返回课程状态。课程状态只能根据本浏览器已完成的课程操作判断。</p></div>
        </section>
      </aside>
    </div>
  </div>
</template>
