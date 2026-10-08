学习会话接口更新需要先迁移已有数据库，再启动新版后端。

已有数据库执行同目录 `20261008_standalone_learning_sessions.sql`；新建数据库使用更新后的 `learningAgentSql.sql`。迁移脚本按列和约束的存在情况处理，可重复执行，不删除任何会话、消息、记忆或计划，也不会覆盖已经保存的独立会话模式。

原有会话仍有课程编号，新增字段默认 `COURSE` 表示其来自课程入口。原有消息的 `agent_mode` 不变，旧接口不传 `mode` 时继续展示所有历史；前端传 `mode=COURSE`、`CHAT` 或 `FOCUS` 时只展示相应模式。

新增接口：

- `GET /learning-agent/learning/sessions`：当前登录用户全部未删除会话，包括进行中、已完成、课程、问答和专注；按最近活动倒序。
- `POST /learning-agent/learning/standalone-session`：请求 `{"sessionTitle":"理解线程","mode":"CHAT"}` 或 `FOCUS`，不需要课程编号；返回新的会话编号。
- `GET /learning-agent/learning/session/{id}/messages?mode=CHAT`：按模式查看当前用户会话历史，消息返回 `agentMode`。

三种会话创建时都固定模式，正常聊天从后端保存的会话模式读取；不传 `mode` 或传错 `mode` 不会改变执行模式。旧课程会话传 `CHAT`/`FOCUS` 的普通新请求现在按照 `COURSE` 执行，因此需要先初始化课程学习进度。

切换问答与专注后必须使用新会话编号。这样不仅页面清空，后端的消息、会话记忆与专注计划也不会串到另一个模式。

已经暂停待审批的旧课程任务继续使用检查点保存的原 `CHAT`/`FOCUS` 模式、工具调用和进度快照完成原任务，不把它们临时改成 `COURSE` 后执行；独立会话的检查点模式则必须与会话固定模式一致。旧展示历史不删除也不重分类。

本次只保存迁移脚本，没有对正在运行的数据库执行迁移，也没有重启已有后端。单元测试与接口测试使用替身，不代表已经完成真实 MySQL 联调。
