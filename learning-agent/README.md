chore(docker): 完善中间件部署配置与本地启动指南

- 配置 Compose 仅运行中间件，不构建或启动前后端容器
- 替换 MinIO 镜像，固定版本与摘要并适配健康检查
- 完善环境变量模板和 MySQL 首次初始化配置
- 补充新电脑部署、后端连接验证及常见问题排查文档## **项目简介**

一个将AI当作你专有课程老师的Agent。你可以利用它来提高学习东西的效率。

## 系统上下文

┌─────────────┐
│   学生                           │
│ (浏览器/客户端)       │
└──────┬──────┘
       │ HTTP 请求
       │ (创建会话/完成会话/发布课程)
       ▼
┌──────────────────────────┐
│   Learning Agent 后端                                  |
│   (Spring Boot)                                               |
│   Controller→Service→                               |
│   Repository(MyBatis)                                │
└──────┬───────────────────┘
       │ JDBC
       ▼
┌─────────────┐
│   MySQL                     │
│ users/courses         │
│ /sessions                   │
└─────────────┘

## **技术栈**

后端采用springboot，作为主要框架。MyBatis框架用来便捷访问数据库。docker来进行部署。

## 新电脑启动指南（Windows / PowerShell）

本指南采用 **Docker 运行中间件，本机运行 Java 后端** 的方式。适用于全新电脑和空数据卷，不需要复制旧电脑的数据库、MinIO 文件或 Milvus 元数据。

- `docker-compose.yml` 只包含 MySQL、MinIO、etcd、Milvus、Attu。
- 不构建、不启动本项目的前端或后端镜像；这些命令不会执行后端 `Dockerfile`。
- 后端使用本机 JDK 和 Maven 运行；如果需要网页界面，前端也可以在本机启动。
- 下列步骤默认在 Windows 的 **PowerShell** 中执行。步骤 2 之后，除前端步骤外，工作目录始终是 `LearningAgent/learning-agent`。

### 1. 安装并检查工具

| 工具 | 要求 / 用途 |
| --- | --- |
| Git | 获取项目代码 |
| Docker Desktop | 已启动 Docker Engine，使用 Linux containers 模式 |
| JDK 21 | 与后端 `pom.xml` 的 Java 版本一致 |
| Maven | 本项目此前使用 Maven 3.9.4 验证；本机需要可用的 Maven 命令 |
| Node.js / npm | 仅在需要运行网页前端时安装，见步骤 7 |

安装完成后重新打开 PowerShell，检查：

```powershell
git --version
java -version
mvn -version
docker version
docker compose version
docker info --format '{{.OSType}}'
```

确认 `java` 和 `mvn -version` 输出中的 Java 都为 21，Docker 同时显示 Client 和 Server，最后一条命令输出 `linux`。只安装 Docker Desktop 但未启动引擎时，不能继续拉取或启动容器。

### 2. 获取代码并进入后端目录

从你希望存放项目的目录执行：

```powershell
git clone https://github.com/yj-joker/LearningAgent.git
Set-Location ./LearningAgent/learning-agent
```

如果已经复制或克隆了代码，直接进入对应的 `learning-agent` 目录即可，不必重复克隆。仓库需要认证时，使用有访问权限的 GitHub 账号。

新电脑必须拿到本指南对应的最新 `docker-compose.yml` 和 `.env-example`。本机尚未提交、推送的修改不会通过 `git clone` 自动迁移；如果远程仓库还没有这些修改，可以先复制最新项目源码到新电脑，再单独创建 `.env`，无须复制 `target` 或 Docker 数据卷。

确认当前目录能看到 `pom.xml`、`docker-compose.yml`、`.env-example` 和 `src`。

### 3. 创建并填写 .env

首次创建配置；如果已有 `.env`，下面的命令不会覆盖它：

```powershell
if (-not (Test-Path .env)) {
    Copy-Item .env-example .env
}
notepad .env
```

至少检查以下配置，保存文件后继续：

| 配置项 | 新电脑怎么填写 |
| --- | --- |
| `MYSQL_HOST` | 保持 `127.0.0.1`；Java 在本机运行 |
| `MYSQL_PORT` | 默认 `3306`；被其他 MySQL 占用时改为 `3307` |
| `MYSQL_DATABASE` | 保持 `learning_agent`，与项目初始化 SQL 一致 |
| `MYSQL_USER` | 保持 `root`；当前 Compose 按 root 账号配置 |
| `MYSQL_PASSWORD` | 填写非空数据库密码；同一个值供 Docker 初始化和 Java 连接使用 |
| `JWT_SECRET` | 填写随机生成的 Base64 密钥，生成方法见下方 |
| `MINIO_ENDPOINT` | 默认 `http://127.0.0.1:9000` |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 与模板一致即可用于本地开发；修改后 Java、MinIO、Milvus 会共同读取新配置 |
| `MINIO_BUCKET` | 保持 `document-bucket` |
| `MILVUS_HOST` / `MILVUS_PORT` | 保持 `127.0.0.1` / `19530` |
| `ALIYUN_EMBEDDING_API_KEY` | 填写你自己的阿里云 API Key，用于文本向量化 |
| `ALIYUN_LLM_API_KEY` | 可选；需要独立聊天模型凭证时增加这一行，未设置时回退使用 Embedding Key |

生成 JWT 密钥的 PowerShell 命令：

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtRng.GetBytes($jwtBytes)
} finally {
    $jwtRng.Dispose()
}
'JWT_SECRET=' + [Convert]::ToBase64String($jwtBytes)
```

将输出的整行替换 `.env` 中原有的 `JWT_SECRET=` 行，只保留一份。不要把真实密码、JWT 密钥或 API Key 提交到 Git。

填写约定：每个变量独占一行；URL 写纯文本，不写成 Markdown 链接。现有模板中的向量维度为 `1536`，与集合初始化代码一致。阿里云模型名称、地域和权限必须在你的账号下可用；中间件启动成功不代表外部模型调用已经验证。

**端口冲突时：**

- 新电脑不需要另装 MySQL 服务，Compose 会创建 MySQL 容器。
- 如果已有本机 MySQL 占用 3306，可以停止该服务，或把 `.env` 的 `MYSQL_PORT` 改为 `3307`；容器端口仍是 3306，Java 会读取新的宿主机端口。
- 如果修改 `MINIO_API_PORT`，还要同步修改 `MINIO_ENDPOINT` 中的端口。

### 4. 检查配置并下载中间件镜像

先检查配置是否能正确解析；成功时通常没有输出：

```powershell
docker compose --env-file .env config --quiet
```

只有检查成功后，才执行下载：

```powershell
docker compose --env-file .env pull
```

这一步只下载镜像，不创建或启动容器，也不会构建前后端。**全部拉取成功后再进入步骤 5；如果某个镜像失败，先处理本节末尾的排查项。**

当前正式配置的版本：

| 服务 | 镜像版本 / 来源 |
| --- | --- |
| MySQL | `mysql:8.0.34` |
| MinIO | `ghcr.io/coollabsio/minio:RELEASE.2025-10-15T17-29-55Z`，Compose 还固定了 SHA256 摘要 |
| etcd | `quay.io/coreos/etcd:v3.5.5` |
| Milvus | `milvusdb/milvus:v2.3.4` |
| Attu | `zilliz/attu:v2.3.4` |

MinIO 使用 **Coollabs 基于 MinIO 官方源码构建的社区镜像，属于第三方发布**。来源：https://github.com/coollabsio/minio 。该镜像已经完成真实在线拉取验证，无须提供离线安装包；由于镜像没有 `curl`，Compose 使用自带的 `mc` 做健康检查。

本指南使用正式 `docker-compose.yml`。不要附加之前诊断时的 `target/infra-validation/compose.local-cache.yml` 或 `docker-compose.minio-local.yml`，这些文件会覆盖正式镜像和数据卷配置。

单独重试失败的镜像，例如：

```powershell
docker compose --env-file .env pull mysql
docker compose --env-file .env pull minio
```

截至 2026-09-26 的验证边界：MinIO 新镜像在线拉取及项目接口兼容性验证通过；本机后端完整启动曾使用本地已有的 MySQL 8.4.9 做补充验证。正式配置的 **MySQL 8.0.34 在线拉取此前发生超时，尚未完成**，不能把 8.4 的测试当作 8.0.34 在全新电脑上已验证成功。

### 5. 启动中间件并等待就绪

```powershell
docker compose --env-file .env up -d --no-build --wait --wait-timeout 600
docker compose --env-file .env ps -a
```

成功标准：MySQL、MinIO、etcd、Milvus 为 `healthy`；Attu 为 `running / Up`（它没有配置独立的健康检查）。如果命令报错或服务为 `unhealthy / exited`，先查看日志，不要直接认为环境已经就绪。

第一次启动时会自动完成：

1. 创建独立 Compose 项目 `learning-agent-infra` 的网络和空数据卷。
2. MySQL 根据 `.env` 初始化 root 密码，并执行 `src/main/resources/db/migration/learningAgentSql.sql`，创建数据库及业务表。
3. MinIO、etcd 就绪后启动 Milvus，Milvus 就绪后启动 Attu。
4. 业务桶 `document-bucket`、Milvus 集合及索引由下一步启动的后端检查、创建，不需要手工提前创建。

数据库初始化 SQL 只会在空 MySQL 数据卷第一次启动时执行。已有数据卷不会因为修改 `.env` 而自动改密码，也不会重跑建表脚本。新电脑不会带有旧电脑的账号、课程和文档。

### 6. 在本机启动后端并验证连接

仍在 `learning-agent` 目录执行：

```powershell
mvn spring-boot:run
```

首次启动需要下载 Maven 依赖，请保持网络可用。后端保持在这个终端运行；按 `Ctrl+C` 停止。IDE 启动时也要把 Working directory 设置成 `learning-agent`，原因是 `application.yml` 从当前工作目录加载 `./.env`。

启动后确认没有后续的 `Application run failed`，日志能看到数据库连接池就绪、MinIO 桶检查/创建成功、Milvus 初始化完成。只出现 `Started ...` 这一行还不足以确认后续初始化全部成功。

在另一个 PowerShell 窗口执行不写入数据的登录探测：

```powershell
$probeBody = @{
    username = '__infra_probe_nonexistent_user__'
    password = 'invalid_probe_password'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/learning-agent/user/login' -ContentType 'application/json' -Body $probeBody
```

对全新数据库，预期返回“用户名或密码错误”（业务码 `404`），因为探测账号不存在。这表示请求已到达后端并执行数据库查询；不是 HTTP 页面不存在。再通过前端注册自己的用户，不要把探测账号当成系统默认账号。

验证 MinIO 与 Milvus 健康端点（以下为默认端口；修改端口后相应调整）：

```powershell
curl.exe --fail http://127.0.0.1:9000/minio/health/live
curl.exe --fail http://127.0.0.1:9091/healthz
```

默认访问地址：

| 服务 | 地址 / 说明 |
| --- | --- |
| Java 后端 | `http://127.0.0.1:8080`；具体请求需使用业务接口路径 |
| MySQL | `127.0.0.1:3306`，账号和密码来自 `.env` |
| MinIO S3 API | `http://127.0.0.1:9000`，供 Java 和对象存储工具访问 |
| MinIO 控制台 | `http://127.0.0.1:9001` |
| Milvus | `127.0.0.1:19530`，Java 客户端连接端口 |
| Attu 管理界面 | `http://127.0.0.1:8000` |

etcd 仅在 Docker 网络内部使用，不暴露宿主机端口。本机 Java 使用 `127.0.0.1`，容器之间使用 `minio`、`etcd` 等服务名。当前 `application.yml` 默认关闭 Swagger，不应使用 Swagger 页面作为启动成功的判断依据。

### 7. 可选：在本机启动网页前端

只需要调试后端时可以跳过。需要网页界面时安装 Node.js 22.12.0 或更高版本及 npm；当前前端锁定的 Vite 7.3.6 要求 Node.js `^20.19.0 || >=22.12.0`。在另一个 PowerShell 窗口先进入后端 `learning-agent` 目录，再执行：

```powershell
Set-Location ../learning-agent-show
node -v
npm -v
npm install
npm run dev
```

使用终端显示的前端地址（默认 `http://localhost:5173`）。开发服务器会把项目 API 请求代理到本机 8080 后端；保持后端运行。这一步同样不会构建或启动前端 Docker 容器。

### 常用维护命令

以下命令在后端 `learning-agent` 目录执行：

```powershell
# 查看包括已停止容器在内的状态
docker compose --env-file .env ps -a

# 查看中间件日志
docker compose --env-file .env logs --tail 100 mysql minio etcd milvus

# 停止中间件，保留容器和数据卷
docker compose --env-file .env stop

# 再次启动已有环境，无需每次重新下载镜像
docker compose --env-file .env up -d --no-build --wait --wait-timeout 600
```

如果需要移除容器和网络但保留数据，可以使用 `docker compose --env-file .env down`。日常停止不要加 `-v / --volumes`，该选项会删除数据卷。后端在自己的终端按 `Ctrl+C` 停止。

### 常见问题与排查顺序

| 现象 | 先检查什么 |
| --- | --- |
| Docker Server 无法连接 | Docker Desktop 是否已启动且 Linux 引擎就绪 |
| `port is already allocated` / 端口占用 | 是否另有本机 MySQL 或旧容器占用相同端口 |
| 镜像 `TLS handshake timeout` / 下载超时 | 下载链路没有及时完成；检查 Docker 的网络与代理，不能只凭超时就认定代理配置错误 |
| 镜像 `unauthorized / pull access denied` | 镜像地址、版本及仓库权限；这与连接超时是不同问题 |
| 后端 `Connection refused` | 中间件是否健康、宿主机端口是否一致、是否错误地在本机配置了容器服务名 |
| 数据库 `Access denied` | 当前数据卷中的真实密码是否与 `.env` 一致；修改环境变量不会重置旧库密码 |
| 找不到配置项或配置值为空 | 当前工作目录是否为 `learning-agent`，`.env` 是否保存并逐行填写 |
| 对话或向量化报错 | 阿里云 API Key、账号下的模型权限/额度、接口地址，以及向量维度是否符合当前代码 |

**为什么 Docker Desktop 有 MySQL，之前还会说拉取 MySQL 失败？**

这是两件不同的事：本地已有镜像能够创建容器，不代表现在能从网上下载另一个版本。

- 之前截图中显示的是 `mysql:8.4`。它来自本机原本已有的镜像缓存，我在补充兼容性测试时使用它创建了容器；当时实际数据库版本是 8.4.9。
- 正式 `docker-compose.yml` 指定的是 `mysql:8.0.34`。此前失败的是这个版本的在线拉取，不是“电脑中没有任何 MySQL”。
- Containers 页面列出容器，包括已经停止的容器；Images 页面才用于查看本地镜像。界面上出现一行 MySQL 不表示它正在运行，也不说明对应版本刚刚在线下载成功。
- 当时 8.4 测试使用了单独的 `mysql84-validation-data` 数据卷和临时覆盖文件，没有把正式配置改成 8.4。不要把该 8.4 数据卷直接交给 8.0.34 使用。

可以通过下面的只读命令自行确认版本与状态：

```powershell
docker image ls mysql
docker ps -a --filter 'name=learning-agent-infra-mysql' --format '{{.Names}} | {{.Image}} | {{.Status}}'
docker compose --env-file .env config --images
```

## **如何测试？**

在当前目录下的终端中执行：

```sh
mvn test
```

执行完毕之后即可运行当前项目的所有测试

## **接口列表**

### 学习会话接口

POST  /learning-agent/learning/session  创建学习会话

PUT    /learning-agent/learning/session/completed/{learningSessionId}  完成学习会话

### 课程接口

POST   /learning-agent/courses/createCourse  创建课程

PUT     /learning-agent/courses/publishCourse/{courseId}  发布课程

## 已知限制 


