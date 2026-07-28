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

浏览器访问 `http://localhost:5173`。开发环境会把 `/learning-agent` 和 `/v3` 请求代理到后端的 8080 端口。

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
| 提交课程审核 | PUT | `/learning-agent/courses/publishCourse/{courseId}` |
| 管理员审核通过课程 | PUT | `/learning-agent/courses/passCourse/{courseId}` |
| 管理员驳回或下架课程 | PUT | `/learning-agent/courses/rejectCourse/{courseId}` |
| 创建学习会话 | POST | `/learning-agent/learning/session` |
| 完成学习会话 | PUT | `/learning-agent/learning/session/completed/{learningSessionId}` |

## 当前后端接口限制

1. 没有课程列表和学习会话列表接口，因此页面不能读取数据库中的完整列表。
2. 创建课程响应现在包含 `courseId`，前端会自动填入提交审核区域；创建学习会话响应仍没有会话 ID。
3. 登录响应和 JWT 均包含角色信息；前端据此分流界面，后端仍通过管理员切面执行最终权限校验。
4. 页面上的“操作记录”只保存在当前浏览器的 `localStorage`，用于反馈已成功完成的接口调用，不等同于数据库数据。

## 认证接入说明

- 登录成功后，前端把 `UserVO.token` 保存在 `localStorage`，课程和学习会话请求自动添加 `Authorization: Bearer <token>`。
- 注册接口当前响应的 `token` 为 `null`，所以注册成功后必须重新登录。
- 登录和注册接口已加入后端拦截器白名单；其他业务请求必须携带有效 JWT。

## 用户端与管理端

- 统一登录入口：`/login`。`USER` 登录后进入用户端，`ADMIN` 登录后自动进入管理端。
- 管理员也可直接访问 `/admin/login`；只有 `ADMIN` 账号可以进入 `/admin/users` 和 `/admin/courses`。
- 普通用户界面不会显示管理入口、管理员接口说明或课程审核操作。
- 管理端支持分页查看全部用户、用户名模糊搜索、角色筛选、注册时间范围筛选和每页数量切换。
- 管理端支持按课程 ID 执行审核通过、驳回和下架。由于后端没有课程查询接口，暂不展示待审核课程列表。

## 课程状态流转

```text
创建课程：PRIVATE
用户提交审核：PRIVATE → PENDING
管理员审核通过：PENDING → PUBLISHED
管理员驳回或下架：PENDING / PUBLISHED → PRIVATE
```
