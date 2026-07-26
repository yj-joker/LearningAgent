# 阶段一：使用 Maven + Java 21 编译项目
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace

# 先复制 pom.xml，方便利用 Docker 缓存 Maven 依赖
COPY pom.xml .

RUN mvn -B dependency:go-offline

# 再复制源代码
COPY src ./src

# 打包 Spring Boot 项目
RUN mvn -B clean package -DskipTests


# 阶段二：只使用 JRE 运行项目
FROM eclipse-temurin:21-jre

WORKDIR /app

# 从构建阶段复制生成的 Jar
COPY --from=builder /workspace/target/*.jar app.jar

# 声明应用在容器内监听 8080
EXPOSE 8080

# 容器启动时执行
ENTRYPOINT ["java", "-jar", "/app/app.jar"]