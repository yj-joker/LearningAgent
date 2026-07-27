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
| 创建课程 | POST | `/learning-agent/courses/createCourse` |
| 发布课程 | PUT | `/learning-agent/courses/publishCourse/{courseId}` |
| 创建学习会话 | POST | `/learning-agent/learning/session` |
| 完成学习会话 | PUT | `/learning-agent/learning/session/completed/{learningSessionId}` |

## 当前后端接口限制

1. 没有课程列表和学习会话列表接口，因此页面不能读取数据库中的完整列表。
2. 创建课程响应没有课程 ID，创建学习会话响应没有会话 ID；发布和完成操作需要手动输入数据库 ID。
3. 没有登录/认证接口，而业务层依赖 `BaseContext` 的当前用户 ID。真实调用前需确保后端已有设置当前用户上下文的机制。
4. 页面上的“操作记录”只保存在当前浏览器的 `localStorage`，用于反馈已成功完成的接口调用，不等同于数据库数据。

## 认证接入说明

- 登录成功后，前端把 `UserVO.token` 保存在 `localStorage`，课程和学习会话请求自动添加 `Authorization: Bearer <token>`。
- 注册接口当前响应的 `token` 为 `null`，所以注册成功后必须重新登录。
- 当前后端 `WebMvcConfig` 的登录白名单仍是 `/weixiu/user/login` 和 `/weixiu/user/register`，与新控制器的 `/learning-agent/user/login`、`/learning-agent/user/register` 不一致。若不修正白名单，登录和注册会先被 `LoginInterceptor` 拦截并返回“用户未登录”，前端无法完成真实认证。
