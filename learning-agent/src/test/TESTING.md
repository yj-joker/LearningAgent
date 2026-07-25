# LearningAgent 测试代码结构

测试代码按“测试层级 + 被测试类”进行分类，并尽量与 `src/main/java` 中的包结构保持一致。

```text
src/test/java/com/yjjoker/learningagent/
├── LearningAgentApplicationTests.java          Spring 上下文启动测试
├── service/impl/                               Service 单元测试
│   └── LearningSessionServiceImplTest.java
├── controller/                                 Controller 接口测试
├── repository/                                 Repository 数据访问测试
├── entity/                                     实体自身行为测试
├── integration/                                多层协作的集成测试
└── testsupport/                                多个测试共用的测试工具和数据工厂
```

## 新测试应该写在哪里

### Service 业务逻辑测试

生产代码：

```text
src/main/java/com/yjjoker/learningagent/service/impl/CoursesServiceImpl.java
```

测试代码：

```text
src/test/java/com/yjjoker/learningagent/service/impl/CoursesServiceImplTest.java
```

一个 Service 实现类通常对应一个测试类。测试类内部使用 `@Nested` 按业务方法分类。

### Controller 接口测试

生产代码：

```text
src/main/java/com/yjjoker/learningagent/controller/LearningSessionController.java
```

测试代码：

```text
src/test/java/com/yjjoker/learningagent/controller/LearningSessionControllerTest.java
```

用于测试请求参数、HTTP 状态码和响应内容。

### Repository 数据访问测试

生产代码：

```text
src/main/java/com/yjjoker/learningagent/repository/CoursesRepository.java
```

测试代码：

```text
src/test/java/com/yjjoker/learningagent/repository/CoursesRepositoryTest.java
```

用于测试数据保存、查询和映射。

### Entity 实体行为测试

生产代码：

```text
src/main/java/com/yjjoker/learningagent/entity/Courses.java
```

测试代码：

```text
src/test/java/com/yjjoker/learningagent/entity/CoursesTest.java
```

只在实体包含判断、计算等业务行为时编写，不需要测试普通 getter 和 setter。

### Integration 集成测试

测试 Controller、Service、Repository 等多层组合流程时，放在：

```text
src/test/java/com/yjjoker/learningagent/integration/
```

类名使用 `IntegrationTest` 结尾，例如：

```text
LearningSessionIntegrationTest.java
```

### testsupport 公共测试支持代码

当多个测试类重复创建相同 DTO、实体或测试数据时，将公共构造代码放在：

```text
src/test/java/com/yjjoker/learningagent/testsupport/
```

例如：

```text
LearningSessionTestDataFactory.java
```

不要在这里编写真正的 `@Test` 测试方法。

## 命名约定

- 测试类：`被测试类名 + Test`
- 集成测试：`业务名称 + IntegrationTest`
- 测试方法：`should预期结果When触发条件`
- 中文说明：使用 `@DisplayName`

示例：

```java
@Test
@DisplayName("课程不存在时，应抛出课程不存在异常")
void shouldThrowCourseNotFoundExceptionWhenCourseDoesNotExist() {
}
```

## 测试类内部结构

```java
@DisplayName("某业务服务测试")
class SomeServiceImplTest {

    @BeforeEach
    void setUp() {
        // 每个测试共用的初始化
    }

    @Nested
    @DisplayName("创建某业务")
    class CreateTests {
        // 创建成功、参数错误、权限不足等场景
    }

    @Nested
    @DisplayName("查询某业务")
    class FindTests {
        // 查询成功、数据不存在等场景
    }
}
```

每个 `@Test` 只验证一个独立场景，测试之间不要依赖执行顺序。
