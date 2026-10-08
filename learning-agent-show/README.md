# Learning Agent Show

基于 `learning-agent` Spring Boot 接口实现的 Vue 3 前端，前后端在独立目录中开发。学习会话列表、历史消息与固定模式需要配套新版后端。

## 技术栈

- Vue 3 + TypeScript
- Vite
- Vue Router
- Lucide 图标
- 原生 Fetch API

## 本地运行

先启动后端（默认 `http://localhost:8080`），再启动前端：

```bash
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。开发环境会把 `/learning-agent` 和后端当前使用的章节根路径请求代理到 8080 端口。

已有数据库需要先执行 [学习会话增量迁移](../learning-agent/src/main/resources/db/migration/20261008_standalone_learning_sessions.sql)，再启动新版后端；新建数据库使用更新后的 `learningAgentSql.sql`。本次代码验证尚未对正在运行的数据库执行迁移或重启后端，真实数据库联调仍待完成。

## 生产构建

```bash
npm run build
npm run preview
```

跨域部署时，可以复制 `.env.example` 为 `.env.production`，将 `VITE_API_BASE_URL` 配置为后端完整地址。

## 已接入接口

| 功能 | 方法 | 路径 |
| --- | --- | --- |
| 用户注册 | POST | `/learning-agent/user/register` |
| 用户登录 | POST | `/learning-agent/user/login` |
| 管理员分页查询用户 | GET | `/learning-agent/user/pageQuery` |
| 创建课程 | POST | `/learning-agent/courses/createCourse` |
| 提交课程审核 | PATCH | `/learning-agent/courses/publishCourse/{courseId}` |
| 管理员审核通过课程 | PATCH | `/learning-agent/courses/passCourse/{courseId}` |
| 管理员驳回或下架课程 | PATCH | `/learning-agent/courses/rejectCourse/{courseId}` |
| 批量添加章节 | POST | `/createChapters` |
| 查询课程章节 | GET | `/getChaptersByCourseId/{courseId}` |
| 批量修改章节 | PUT | `/updateChapters` |
| 批量删除章节 | DELETE | `/deleteChaptersByIds/{ids}` |
| 创建知识点 | POST | `/knowledgePoints/createKnowledgePoint` |
| 查询章节知识点 | GET | `/knowledgePoints/chapter/{chapterId}` |
| 批量修改知识点 | PUT | `/knowledgePoints/updateKnowledgePoints` |
| 批量删除知识点 | DELETE | `/knowledgePoints/{ids}` |
| 创建知识点关系/提交关系建议 | POST | `/knowledgePointRelations/createKnowledgePointRelations` |
| 删除知识点关系（仅管理员接口，前端用户端不开放） | DELETE | `/knowledgePointRelations/deleteKnowledgePointRelations/{ids}` |
| 查询课程已生效的前置知识点 | GET | `/knowledgePoints/getPrerequisiteKnowledgePoints/{courseId}` |
| 查询课程已生效的易混淆知识点 | GET | `/knowledgePoints/getConfusableKnowledgePoints/{courseId}` |
| 创建知识库 | POST | `/knowledgeBase/add` |
| 上传知识库文档 | POST | `/document/upload/{kbId}` |
| 下载知识库文档 | GET | `/document/download/{documentId}` |
| 创建学习会话 | POST | `/learning-agent/learning/session` |
| 创建独立问答或专注会话 | POST | `/learning-agent/learning/standalone-session` |
| 当前用户全部未删除会话 | GET | `/learning-agent/learning/sessions` |
| 学习会话历史消息 | GET | `/learning-agent/learning/session/{learningSessionId}/messages`，可选 `mode` 参数 |
| 完成学习会话 | PUT | `/learning-agent/learning/session/completed/{learningSessionId}` |
| 开始课程学习 | POST | `/learning-agent/learning/course-progress/{sessionId}` |
| 读取已保存的课程进度 | GET | `/learning-agent/learning/course-progress/{sessionId}` |
| 读取已保存的专注目标与步骤 | GET | `/agent/sessions/{sessionId}/progress` |
| AI 助教对话 | POST | `/agent/chat` |

## 当前后端接口限制

1. 没有课程列表和课程详情接口，因此页面不能读取数据库中的完整课程列表，也不能通过课程 ID 查询课程状态；课程选择仍依赖当前浏览器记录的课程。
2. 学习会话列表与历史消息已通过后端接口读取，包含当前用户的课程、问答、专注会话及已完成会话；已删除会话不返回，浏览器操作记录不再作为会话列表或聊天历史来源。
3. 登录响应和 JWT 均包含角色信息；前端据此分流界面，后端仍通过管理员切面执行最终权限校验。
4. 页面上的“操作记录”只保存在当前浏览器的 `localStorage`，用于反馈已成功完成的接口调用，不等同于数据库数据。
5. 章节查询会返回排序后的章节，但不返回课程状态。前端只有在本地课程记录可以确认状态为 `PRIVATE` 时才启用拖拽。

## 认证接入说明

- 登录成功后，前端把 `UserVO.token` 保存在 `localStorage`，课程和学习会话请求自动添加 `Authorization: Bearer <token>`。
- 注册接口当前响应的 `token` 为 `null`，所以注册成功后必须重新登录。
- 登录和注册接口已加入后端拦截器白名单；其他业务请求必须携带有效 JWT。

## 用户端与管理端

- 统一登录入口：`/login`。`USER` 登录后进入用户端，`ADMIN` 登录后自动进入管理端。
- 管理员也可直接访问 `/admin/login`；只有 `ADMIN` 账号可以进入 `/admin/users`。
- 管理员登录后只能访问管理端；直接访问用户端路由也会被重定向回用户管理页面。
- 普通用户界面不会显示管理入口或管理员操作。
- 用户端章节编排入口为 `/chapters`，用户从课程列表中选择课程，不需要手动填写课程 ID。
- 管理端支持分页查看全部用户、用户名模糊搜索、角色筛选、注册时间范围筛选和每页数量切换。
- 后端提供课程审核动作但没有待审核课程列表接口，因此前端暂不展示无法形成完整业务流程的课程审核页面。

## 课程状态流转

```text
创建课程：PRIVATE
用户提交审核：PRIVATE → PENDING
管理员审核通过：PENDING → PUBLISHED
管理员驳回或下架：PENDING / PUBLISHED → PRIVATE
```

## 章节排序

- 新章节默认追加到末尾，`sortOrder` 从 `1000` 开始并按 `1000` 递增。
- PRIVATE 课程通过拖动章节卡片调整顺序，不提供上移、下移按钮。
- 移动到两个章节之间时，新排序值为前后 `sortOrder` 的中间整数。
- 排序空间不足、唯一索引冲突或请求失败时，前端不会保留错误顺序。
- 章节 ID 使用雪花算法生成，前端通过无精度损失 JSON 解析并始终按字符串传递。

## 知识点管理

- 用户从课程和章节列表进入知识点管理，不需要手动填写任何数据库 ID。
- 支持知识点创建、查询、修改和删除，名称与描述按照后端长度限制校验。
- 新知识点默认添加到章节末尾，`sortOrder` 从 `1000` 开始并按 `1000` 递增。
- PRIVATE 课程支持拖动知识点卡片调整顺序，其他状态只锁定拖动排序。
- 支持从当前章节已加载的知识点中选择目标，创建“前置关系”或“易混淆关系”。创建成功后展示本次会话中的关系记录。
- 当前后端提供的是课程维度的已生效知识点查询，但尚未提供完整的关系边详情和关系更新接口；页面刷新后会重新加载已生效关联知识点，本次新增记录提示会清空。删除关系接口仅允许管理员调用，用户端不提供删除入口。

## 知识库与文档

- 用户从自己的课程列表选择课程后创建知识库，不需要手动填写课程 ID 或知识库 ID。
- 用户知识库默认为私有；只有课程处于 `PRIVATE` 状态时，课程拥有者才能上传文档。
- 上传使用 `multipart/form-data`，下载直接处理后端返回的文件流和文件名。
- 当前后端没有知识库列表和文档列表查询接口，因此页面只展示本次打开页面后创建、上传的记录；刷新后记录会清空，但服务端数据不会被删除。
- 管理员虽然可以在后端创建系统知识库，但当前没有课程列表接口供管理端选择目标课程，因此管理端不提供要求手动输入课程 ID 的临时页面。

## AI 助教

- 课程学习位于 `/ai-assistant`，独立问答位于 `/ai-assistant/chat`，专注学习位于 `/ai-assistant/focus`。每个页面只列出服务器保存的同模式会话。
- 课程学习保留选择课程和填写目标的入口；独立问答与专注无需课程，在首条消息发送时创建数据库会话。
- 会话模式在创建时保存，后端按照该模式执行。聊天请求仍可携带 `mode` 以兼容已有客户端，但该字段不能改变会话的模式。
- 问答与专注切换后显示空白对话；再次发送会创建新会话编号。历史记录保留在数据库，用户可从对应页面的历史列表或“全部会话”明确选择并恢复。
- 刷新页面或更换浏览器后，仍可读取服务器保存的同一会话历史。独立会话按模式读取；课程页保留旧课程会话的完整展示历史，新消息按 `COURSE` 执行，模型上下文仍按模式隔离。
- 课程发送前显式初始化课程进度；重复初始化不会重置已有进度。等待审批的任务从后端读取并恢复，旧课程任务沿用审批时保存的模式完成。
- 已完成的学习会话不能继续对话，管理员端不展示 AI 助教入口。
- 专注和课程页面展示可折叠的已保存进度；完成数量只根据后端状态计算，取消步骤或移除知识点不进入总数。查看历史不会初始化进度，已完成会话也能查看快照；课程内容变化时显示提示。
- 专注进度查询只允许访问当前用户的专注会话，尚未创建目标时返回空结果。新增查询入口需要更新并重启后端。

## 计划编辑与审批展示

- 学习计划的保存操作固定在页面底部，长计划滚动时仍可使用。切换草案或离开页面前，未保存修改会提示确认；刷新或关闭标签页由浏览器提醒。
- 审批优先展示拟修改内容、完成条件及依据，引用与版本收进“技术详情”，完整参数仍可查看。“记忆变更”数量只统计待确认的记忆申请，会话工具审批从会话页面查看。
