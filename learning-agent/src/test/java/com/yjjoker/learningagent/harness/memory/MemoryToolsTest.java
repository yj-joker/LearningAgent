package com.yjjoker.learningagent.harness.memory;

import com.yjjoker.learningagent.entity.LearningSession;
import com.yjjoker.learningagent.harness.memory.model.*;
import com.yjjoker.learningagent.harness.memory.service.*;
import com.yjjoker.learningagent.harness.tool.impl.*;
import com.yjjoker.learningagent.projectenum.LearningSessionStatusEnum;
import com.yjjoker.learningagent.repository.LearningSessionRepository;
import com.yjjoker.learningagent.utils.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static com.yjjoker.learningagent.harness.memory.MemoryTestData.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryOperation.*;
import static com.yjjoker.learningagent.harness.memory.model.MemoryScope.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 不连接模型或数据库；验证工具入口确实执行参数、当前用户和目标检查。
class MemoryToolsTest {
    private final JsonMapper json = new JsonMapper();
    private final MemoryReferenceRegistry refs = new MemoryReferenceRegistry();
    private final StructuredMemoryService store = mock(StructuredMemoryService.class);
    private final MemoryCandidatePersistenceService persistence = mock(MemoryCandidatePersistenceService.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final MemoryApprovalService approvals = mock(MemoryApprovalService.class);
    private final MemoryToolService service = new MemoryToolService(refs, store, sessions, approvals);

    // 用固定用户和活动会话建立服务端请求范围。
    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        LearningSession session = new LearningSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus(LearningSessionStatusEnum.ACTIVE);
        when(sessions.findSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        begin("请记住我喜欢篮球");
    }

    // 请求线程会复用，每个测试后清理两份线程状态。
    @AfterEach
    void clear() {
        refs.clear();
        BaseContext.removeCurrentId();
    }

    // 三个写工具声明写操作；列表只读，四种工具结果都不能跨请求重放。
    @Test
    void shouldDeclareToolCapabilitiesAndSchemas() {
        for (var tool : List.of(new CreateMemoryTool(service), new UpdateMemoryTool(service), new DeleteMemoryTool(service))) {
            assertTrue(tool.isMemoryWriteTool());
            assertTrue(tool.isContextScopedTool());
            assertEquals(false, tool.parametersSchema().get("additionalProperties"));
        }
        assertFalse(new ListMemoriesTool(service).isMemoryWriteTool());
        assertTrue(new ListMemoriesTool(service).isContextScopedTool());
    }

    // 写工具只创建审批申请，不在用户确认前触碰记忆持久化服务。
    @Test
    void shouldCreatePendingApproval() {
        var request = new MemoryApprovalRequest();
        request.setId(8L);
        when(approvals.create(anyLong(), anyLong(), any(), any())).thenReturn(request);
        var result = new CreateMemoryTool(service).execute(createArguments("请记住我喜欢篮球"));
        assertTrue(result.isSuccess());
        assertNull(result.memoryWriteReceipt());
        assertTrue(result.getContent().contains("PENDING_APPROVAL"));
        verify(approvals).create(eq(USER_ID), eq(SESSION_ID), any(), any());
        verifyNoInteractions(persistence);
    }

    // 模型试图添加用户 ID 等越权字段时，不能只依赖 Schema 提示。
    @Test
    void shouldRejectAdditionalOwnerParameter() {
        var node = json.readTree(createArguments("请记住我喜欢篮球")).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) node).put("userId", 999);
        assertFalse(service.write(CREATE, node.toString()).isSuccess());
        verifyNoInteractions(persistence);
    }

    // 历史中的请求或助手自己生成的请求，不属于本轮用户依据。
    @Test
    void shouldRejectEvidenceAbsentFromCurrentMessage() {
        begin("你好");
        assertFalse(service.write(CREATE, createArguments("请记住我喜欢篮球")).isSuccess());
        verifyNoInteractions(persistence);
    }

    // 否定、教学提问和引用中的命令都不能截取后冒充明确授权。
    @Test
    void shouldRejectNegatedQuotedOrQuestionInstructions() {
        for (String input : List.of("不要请记住我喜欢篮球", "有人说请记住我喜欢篮球", "请记住我喜欢篮球？")) {
            begin(input);
            assertFalse(service.write(CREATE, createArguments(input.endsWith("？") ? input : "请记住我喜欢篮球")).isSuccess());
        }
        verifyNoInteractions(persistence);
    }

    // 登录用户与请求上下文不一致时，不能使用当前引用写入。
    @Test
    void shouldRejectForeignUser() {
        BaseContext.setCurrentId(USER_ID + 1);
        var result = service.write(CREATE, createArguments("请记住我喜欢篮球"));
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("MEMORY_ACCESS_DENIED", result.getErrorCode());
        verifyNoInteractions(persistence);
    }

    // 即使用户相同，已结束或被删除的会话也不允许继续写入。
    @Test
    void shouldRejectInactiveSession() {
        sessions.findSessionById(SESSION_ID).orElseThrow().setStatus(LearningSessionStatusEnum.CANCELED);
        assertFalse(service.write(CREATE, createArguments("请记住我喜欢篮球")).isSuccess());
        verifyNoInteractions(persistence);
    }

    // 不存在的引用不能靠猜数据库 ID 修复。
    @Test
    void shouldRejectUnknownReference() {
        begin("请删除运动偏好");
        assertFalse(service.write(DELETE, deletion(List.of("memory_999"))).isSuccess());
        verifyNoInteractions(persistence);
    }

    // 同一写操作不能混用用户表和会话表中的目标。
    @Test
    void shouldRejectMixedScopeAndDuplicateTargets() {
        begin("请删除运动偏好");
        String userRef = refs.registerUserMemory(user(1, "sport", "喜欢篮球"));
        String sessionRef = refs.registerSessionMemory(session(1, "sport", "本次练球"));
        assertFalse(service.write(DELETE, deletion(List.of(userRef, sessionRef))).isSuccess());
        assertFalse(service.write(DELETE, deletion(List.of(userRef, userRef))).isSuccess());
        verifyNoInteractions(persistence);
    }

    // 删除同样先进入审批，不因操作类型不同而绕过确认。
    @Test
    void shouldDeleteAliasesTogether() {
        begin("请删除运动偏好");
        String first = refs.registerUserMemory(user(1, "sport", "喜欢篮球"));
        String second = refs.registerUserMemory(user(2, "favoriteSport", "喜欢篮球"));
        var request = new MemoryApprovalRequest();
        request.setId(9L);
        when(approvals.create(anyLong(), anyLong(), any(), any())).thenReturn(request);
        var result = new DeleteMemoryTool(service).execute(deletion(List.of(first, second)));
        assertTrue(result.isSuccess());
        assertNull(result.memoryWriteReceipt());
        assertNotNull(refs.resolve(first));
        assertNotNull(refs.resolve(second));
        verifyNoInteractions(persistence);
    }

    // 审批申请创建成功后，工具只返回申请编号。
    @Test
    void shouldReturnApprovalId() {
        var request = new MemoryApprovalRequest();
        request.setId(10L);
        when(approvals.create(anyLong(), anyLong(), any(), any())).thenReturn(request);
        var result = service.write(CREATE, createArguments("请记住我喜欢篮球"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("10"));
        verifyNoInteractions(persistence);
    }

    // 审批前不会刷新或改变模型已经看到的记忆索引。
    @Test
    void shouldNotRefreshUnrelatedSnapshotsSilently() {
        begin("请修改运动偏好为足球");
        String ref = refs.registerUserMemory(user(1, "sport", "喜欢篮球"));
        String untouched = refs.registerUserMemory(user(2, "nickname", "称呼小林"));
        var request = new MemoryApprovalRequest();
        request.setId(11L);
        when(approvals.create(anyLong(), anyLong(), any(), any())).thenReturn(request);
        String input = json.writeValueAsString(Map.of("targetMemoryRefs", List.of(ref),
                "userEvidence", "请修改运动偏好为足球", "memoryTopic", "运动",
                "memorySummary", "喜欢足球", "memoryContent", "喜欢足球"));
        var result = new UpdateMemoryTool(service).execute(input);
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("PENDING_APPROVAL"));
        assertEquals("称呼小林", refs.toolContext().resolve(untouched).getMemorySummary());
        verify(store, never()).loadUserMemoryIndex(any());
    }

    // 审批申请保存失败时，不能伪造待审批编号或写入成功。
    @Test
    void shouldNotIssueReceiptOnApprovalFailure() {
        when(approvals.create(anyLong(), anyLong(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库故障"));
        var result = service.write(CREATE, createArguments("请记住我喜欢篮球"));
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertNull(result.memoryWriteReceipt());
    }

    // 刷新只改变索引内容，不按新排序重新分配旧编号。
    @Test
    void shouldKeepReferencesStableAndNotReuseDeletedNumbers() {
        var first = user(1, "first", "第一条");
        var second = user(2, "second", "第二条");
        refs.refresh(new MemoryIndexSnapshot(List.of(first, second), List.of()));
        refs.refresh(new MemoryIndexSnapshot(List.of(second, first), List.of()));
        assertEquals(1L, refs.resolve("memory_1").getMemoryId());
        refs.refresh(new MemoryIndexSnapshot(List.of(second, user(3, "third", "第三条")), List.of()));
        assertNull(refs.resolve("memory_1"));
        assertEquals(3L, refs.resolve("memory_3").getMemoryId());
    }

    // 列表只返回索引，不把完整正文、数据库 ID 或其他用户内容暴露给模型。
    @Test
    void shouldListOnlyScopedIndex() {
        var memory = user(900123, "sport", "运动偏好");
        memory.setMemoryContent("PRIVATE_BODY");
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(memory));
        var result = new ListMemoriesTool(service).execute("{}");
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("memory_1"));
        assertFalse(result.getContent().contains("900123"));
        assertFalse(result.getContent().contains("PRIVATE_BODY"));
        verifyNoInteractions(persistence);
    }

    // 每个场景重新建立本轮用户依据，避免依赖上个测试的状态。
    private void begin(String input) { refs.beginRun(USER_ID, SESSION_ID, input); }

    // 准备合法新增参数；失败场景只改变需要验证的字段。
    private String createArguments(String evidence) {
        return json.writeValueAsString(Map.of("scope", "USER", "memoryKey", "sport", "memoryTopic", "运动",
                "memorySummary", "喜欢篮球", "memoryContent", "喜欢篮球", "userEvidence", evidence));
    }

    // 删除只提供当前引用和用户依据，不接收数据库 ID 或 key。
    private String deletion(List<String> targets) {
        return json.writeValueAsString(Map.of("targetMemoryRefs", targets, "userEvidence", "请删除运动偏好"));
    }
}
