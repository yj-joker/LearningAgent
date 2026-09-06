# Learning Agent Show

基于 `learning-agent` Spring Boot 项目现有接口实现的 Vue 3 前端。该目录完全独立，不需要修改后端源码。

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
| 创建学习会话 | POST | `/learning-agent/learning/session` |
| 完成学习会话 | PUT | `/learning-agent/learning/session/completed/{learningSessionId}` |

## 当前后端接口限制

1. 没有课程列表、课程详情和学习会话列表接口，因此页面不能读取数据库中的完整课程列表，也不能通过课程 ID 查询课程状态。
2. 创建学习会话响应没有会话 ID，因此前端不展示需要用户手动填写会话 ID 的完成操作。
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
