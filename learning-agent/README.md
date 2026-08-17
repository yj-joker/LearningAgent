## **项目简介**

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

## **如何启动？**

前提条件：

确保**Docker Desktop** 已安装并运行（windows）

```powershell
docker version
docker compose version
```

`git clone  git@github.com:yj-joker/LearningAgent.git`

将代码拉取下来之后，进入./learning-agent目录，执行：

```powershell
docker compose up -d --build
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


