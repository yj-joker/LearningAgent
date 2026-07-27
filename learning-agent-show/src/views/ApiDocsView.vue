<script setup lang="ts">
import { computed, ref } from 'vue'
import { Braces, Check, ChevronDown, Copy, ExternalLink, Info, ServerCog } from 'lucide-vue-next'
import { useToast } from '@/composables/useToast'

interface Endpoint {
  method: 'POST' | 'PUT'
  path: string
  title: string
  description: string
  body?: string
  response: string
  notes: string[]
}

const { showToast } = useToast()
const openIndex = ref(0)
const filter = ref<'ALL' | 'POST' | 'PUT'>('ALL')

const endpoints: Endpoint[] = [
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
    description: '创建一门私有或公开课程，并返回课程展示信息。',
    body: `{
  "courseName": "Java 并发编程",
  "difficultyLevel": 4,
  "learningOutline": "{\\"content\\":\\"线程与锁\\"}",
  "courseType": "PRIVATE"
}`,
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "courseName": "Java 并发编程",
    "publisherName": null,
    "difficultyLevel": 4,
    "learningOutline": "{...}",
    "courseType": "PRIVATE"
  }
}`,
    notes: ['courseType 只能是 PUBLIC 或 PRIVATE', 'difficultyLevel 的数据库约束为 1～5', '当前响应不包含课程 ID'],
  },
  {
    method: 'PUT',
    path: '/learning-agent/courses/publishCourse/{courseId}',
    title: '发布课程',
    description: '将当前用户拥有的私有课程发布为公共课程。',
    response: `{
  "code": "200",
  "message": "OK",
  "data": {
    "courseName": "Java 并发编程",
    "courseType": "PUBLIC"
  }
}`,
    notes: ['courseId 通过路径参数传递', '课程必须存在且属于当前用户', '已经公开的课程不能重复发布'],
  },
  {
    method: 'POST',
    path: '/learning-agent/learning/session',
    title: '创建学习会话',
    description: '为自己拥有的课程或公共课程创建一个 ACTIVE 学习会话。',
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

const visibleEndpoints = computed(() => filter.value === 'ALL' ? endpoints : endpoints.filter((item) => item.method === filter.value))

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
        <p>根据当前 Spring Boot 控制器、DTO 与 VO 整理，共接入 6 个业务接口。</p>
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
          <div><span class="section-kicker">ENDPOINTS</span><h3>业务接口</h3></div>
          <div class="filter-tabs">
            <button v-for="value in ['ALL', 'POST', 'PUT'] as const" :key="value" :class="{ active: filter === value }" @click="filter = value">
              {{ value === 'ALL' ? '全部' : value }}
            </button>
          </div>
        </div>
        <div class="endpoint-list">
          <article v-for="endpoint in visibleEndpoints" :key="endpoint.path" class="endpoint-item" :class="{ open: openIndex === endpoints.indexOf(endpoint) }">
            <button class="endpoint-trigger" @click="openIndex = openIndex === endpoints.indexOf(endpoint) ? -1 : endpoints.indexOf(endpoint)">
              <span class="method-tag" :class="`method-${endpoint.method.toLowerCase()}`">{{ endpoint.method }}</span>
              <span class="endpoint-main"><strong>{{ endpoint.title }}</strong><code>{{ endpoint.path }}</code></span>
              <ChevronDown :size="19" />
            </button>
            <div v-if="openIndex === endpoints.indexOf(endpoint)" class="endpoint-content">
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
          <div><strong>接口限制</strong><p>当前没有课程/会话查询接口，也没有认证接口。工作台仅展示本浏览器记录的成功操作，不代表完整数据库数据。</p></div>
        </section>
      </aside>
    </div>
  </div>
</template>
