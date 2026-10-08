package com.yjjoker.learningagent.harness.tool.impl;

import com.yjjoker.learningagent.harness.course.CourseLearningRunContext;
import com.yjjoker.learningagent.harness.tool.Tool;
import com.yjjoker.learningagent.harness.tool.ToolExecutionResult;
import com.yjjoker.learningagent.service.CourseLearningProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 只读取当前课程会话；查询不会初始化课程或更改知识点状态。
@Component
@RequiredArgsConstructor
@Slf4j
public class GetCourseLearningProgressTool implements Tool {
    private static final JsonMapper JSON = new JsonMapper();
    private final CourseLearningRunContext context;
    private final CourseLearningProgressService service;

    // 注册表自动把名称和定义提供给主模型。
    @Override
    public String name() { return "get_course_learning_progress"; }

    // 模型根据说明判断是否需要重新读取课程事实。
    @Override
    public String description() {
        return "仅课程学习模式可用。读取当前课程的全部章节/知识点索引、真实进度和当前知识点正文；只读，不创建个人计划，不修改进度。不接收参数。";
    }

    // 旧调用不能在未来请求直接重放，课程范围来自当前上下文。
    @Override
    public boolean isContextScopedTool() { return true; }

    // 先确认本轮课程范围，再从数据库刷新；不相信模型提供的任意会话 ID。
    @Override
    public ToolExecutionResult execute(String input) {
        try {
            // 课程查询不接收参数，空输入也不能被解释成默认会话。
            if (input == null || input.length() > 256) {
                return ToolExecutionResult.failure("INVALID_COURSE_ARGUMENTS", "课程查询参数必须是空对象", true);
            }
            JsonNode arguments = JSON.readTree(input);
            if (arguments == null || !arguments.isObject() || !arguments.isEmpty()) {
                return ToolExecutionResult.failure("INVALID_COURSE_ARGUMENTS", "课程查询参数必须是空对象", true);
            }
            Long sessionId = context.require().getSessionId();
            context.bind(service.load(sessionId), context.currentUserMessage());
            String result = context.modelView();
            log.info("课程进度工具读取成功，sessionId={}，resultCharacters={}", sessionId, result.length());
            return ToolExecutionResult.success(result);
        } catch (SecurityException exception) {
            // CHAT 和 FOCUS 没有课程上下文，不允许猜测工具名跨模式访问。
            log.warn("课程进度工具访问被拒绝，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("COURSE_ACCESS_DENIED", "请在课程学习模式中查询课程进度", false);
        } catch (RuntimeException exception) {
            // 失败保留统一工具结果，不把数据库异常正文输出给模型或用户。
            log.warn("课程进度工具读取失败，errorType={}", exception.getClass().getSimpleName());
            return ToolExecutionResult.failure("COURSE_READ_FAILED", "课程进度读取失败，请检查课程和会话状态", false);
        }
    }
}
