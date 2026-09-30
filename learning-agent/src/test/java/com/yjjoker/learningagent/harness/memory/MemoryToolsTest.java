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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 不连接模型或数据库；检查工具只准备预检，且保留参数、归属和目标校验。
class MemoryToolsTest {
    private final JsonMapper json = new JsonMapper();
    private final MemoryReferenceRegistry refs = new MemoryReferenceRegistry();
    private final StructuredMemoryService store = mock(StructuredMemoryService.class);
    private final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
    private final MemoryCandidatePersistenceService persistence = mock(MemoryCandidatePersistenceService.class);
    private final MemoryToolService service = new MemoryToolService(refs, store, sessions, persistence);

    // 建立当前用户和活动会话；工具不能从模型参数取得用户身份。
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

    // 清理线程数据，避免后一个测试继承前一个用户和引用表。
    @AfterEach
    void clear() {
        refs.clear();
        BaseContext.removeCurrentId();
    }

    // 三个写工具都进入审批轮；列表查询保持只读。
    @Test
    void shouldDeclareToolCapabilitiesAndSchemas() {
        for (var tool : List.of(new CreateMemoryTool(service), new UpdateMemoryTool(service), new DeleteMemoryTool(service))) {
            assertTrue(tool.isMemoryWriteTool());
            assertTrue(tool.requiresUserApproval());
            assertTrue(tool.isContextScopedTool());
            assertEquals(false, tool.parametersSchema().get("additionalProperties"));
        }
        assertFalse(new ListMemoriesTool(service).isMemoryWriteTool());
    }

    // 准备完草稿通过不等于审批通过，更不等于记忆已经写入。
    @Test
    void shouldPrepareDraftWithoutWritingOrReportingSuccess() {
        var result = new CreateMemoryTool(service).validateApprovalInput(createArguments("请记住我喜欢篮球"));
        assertTrue(result.isSuccess());
        assertTrue(result.isSuccess());
        assertNull(result.memoryWriteReceipt());
        verifyNoInteractions(store, persistence);
        // 内部草稿含归属和快照，不能意外序列化进模型工具正文。
        assertFalse(json.writeValueAsString(result).contains("userEvidence"));
        assertFalse(json.writeValueAsString(result).contains("approvalDraft"));
    }

    // Schema 不能代替后端检查，额外传入用户 ID 仍然拒绝。
    @Test
    void shouldRejectAdditionalOwnerParameter() {
        var node = (tools.jackson.databind.node.ObjectNode) json.readTree(createArguments("请记住我喜欢篮球"));
        node.put("userId", 999);
        assertFalse(service.validate(CREATE, node.toString()).isSuccess());
        verifyNoInteractions(store, persistence);
    }

    // 历史内容或模型编造的依据不能冒充本轮用户消息。
    @Test
    void shouldRejectEvidenceAbsentFromCurrentMessage() {
        begin("你好");
        var result = service.validate(CREATE, createArguments("请记住我喜欢篮球"));
        assertFalse(result.isSuccess());
        assertEquals("INVALID_MEMORY_ARGUMENT", result.getErrorCode());
    }

    // 不允许删除否定前缀、引用前缀或问号后再提交，必须保留完整原文供审批判断。
    @Test
    void shouldRejectTruncatedUserEvidence() {
        for (String original : List.of("不要请记住我喜欢篮球", "有人说请记住我喜欢篮球", "请记住我喜欢篮球？")) {
            begin(original);
            assertFalse(service.validate(CREATE, createArguments("请记住我喜欢篮球")).isSuccess());
        }
        verifyNoInteractions(store, persistence);
    }

    // 正文中的否定词和问号不是对记忆操作的否定，不能用 contains 一律误杀。
    @Test
    void shouldAllowNegativeFactsAndQuestionContentAsDrafts() {
        for (String original : List.of("请记住我不要吃花生", "请记住我的问题是：什么是事务？", "以后称呼我为小林，保存这个称呼")) {
            begin(original);
            assertTrue(service.validate(CREATE, createArguments(original)).isSuccess());
        }
        verifyNoInteractions(store, persistence);
    }

    // 不声称代码能理解任意句子的授权含义；即使模型误判，完整原文也只能形成待审批草稿。
    @Test
    void shouldNeverTreatQuestionOrNegationAsApprovedExecution() {
        for (String original : List.of("可以记住这个偏好吗？", "不要记住这个偏好")) {
            begin(original);
            var result = service.validate(CREATE, createArguments(original));
            assertTrue(result.isSuccess());
            assertNull(result.memoryWriteReceipt());
        }
        verifyNoInteractions(store, persistence);
    }

    // 当前登录身份与请求范围不同，即使模型提供合法引用也不能通过。
    @Test
    void shouldRejectForeignUser() {
        BaseContext.setCurrentId(USER_ID + 1);
        var result = service.validate(CREATE, createArguments("请记住我喜欢篮球"));
        assertFalse(result.isSuccess());
        assertFalse(result.isRetryable());
        assertEquals("MEMORY_ACCESS_DENIED", result.getErrorCode());
    }

    // 被取消的会话不能继续申请写入。
    @Test
    void shouldRejectInactiveSession() {
        sessions.findSessionById(SESSION_ID).orElseThrow().setStatus(LearningSessionStatusEnum.CANCELED);
        assertFalse(service.validate(CREATE, createArguments("请记住我喜欢篮球")).isSuccess());
    }

    // 模型猜测的引用不能定位数据库记录。
    @Test
    void shouldRejectUnknownReference() {
        begin("请删除运动偏好");
        assertFalse(service.validate(DELETE, deletion(List.of("memory_999"))).isSuccess());
    }

    // 两类记忆不能混成一个操作，同一个目标也不能重复出现。
    @Test
    void shouldRejectMixedScopeAndDuplicateTargets() {
        begin("请删除运动偏好");
        String userRef = refs.registerUserMemory(user(1, "sport", "喜欢篮球"));
        String sessionRef = refs.registerSessionMemory(session(1, "sport", "本次练球"));
        assertFalse(service.validate(DELETE, deletion(List.of(userRef, sessionRef))).isSuccess());
        assertFalse(service.validate(DELETE, deletion(List.of(userRef, userRef))).isSuccess());
    }

    // 删除多个同义目标只产生一个草稿，审批前不删除引用或数据。
    @Test
    void shouldPrepareDeletionWithoutRemovingTargets() {
        begin("请删除运动偏好");
        String first = refs.registerUserMemory(user(1, "sport", "喜欢篮球"));
        String second = refs.registerUserMemory(user(2, "favoriteSport", "喜欢篮球"));
        var result = new DeleteMemoryTool(service).validateApprovalInput(deletion(List.of(first, second)));
        assertTrue(result.isSuccess());
        assertNotNull(refs.resolve(first));
        assertNotNull(refs.resolve(second));
        verifyNoInteractions(store, persistence);
    }

    // 更新只准备新内容，不能提前改变模型已经看到的旧版本。
    @Test
    void shouldNotRefreshSnapshotsBeforeApproval() {
        begin("请修改运动偏好为足球");
        String ref = refs.registerUserMemory(user(1, "sport", "喜欢篮球"));
        String input = json.writeValueAsString(Map.of("targetMemoryRefs", List.of(ref),
                "userEvidence", "请修改运动偏好为足球", "memoryTopic", "运动",
                "memorySummary", "喜欢足球", "memoryContent", "喜欢足球"));
        var result = new UpdateMemoryTool(service).validateApprovalInput(input);
        assertTrue(result.isSuccess());
        assertEquals("喜欢篮球", refs.toolContext().resolve(ref).getMemorySummary());
        verifyNoInteractions(store, persistence);
    }

    // 查询会话时断连就返回失败，不能生成缺少归属校验的草稿。
    @Test
    void shouldNotPrepareDraftOnDatabaseLookupFailure() {
        when(sessions.findSessionById(SESSION_ID))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库故障"));
        var result = service.validate(CREATE, createArguments("请记住我喜欢篮球"));
        assertFalse(result.isSuccess());
        assertEquals("MEMORY_LOOKUP_FAILED", result.getErrorCode());
    }

    // 刷新列表不重新分配旧编号，也不复用已删除的编号。
    @Test
    void shouldKeepReferencesStableAndNotReuseDeletedNumbers() {
        var first = user(1, "first", "第一条");
        var second = user(2, "second", "第二条");
        refs.refresh(new MemoryIndexSnapshot(List.of(first, second), List.of()));
        String firstRef = refs.registerUserMemory(first);
        refs.refresh(new MemoryIndexSnapshot(List.of(second, first), List.of()));
        assertEquals(1L, refs.resolve(firstRef).getMemoryId());
        var third = user(3, "third", "第三条");
        refs.refresh(new MemoryIndexSnapshot(List.of(second, third), List.of()));
        assertNull(refs.resolve(firstRef));
        String thirdRef = refs.registerUserMemory(third);
        assertTrue(thirdRef.endsWith("_3"));
        assertEquals(3L, refs.resolve(thirdRef).getMemoryId());
    }

    // 列表仍只返回索引，不泄露完整正文或数据库 ID。
    @Test
    void shouldListOnlyScopedIndex() {
        var memory = user(900123, "sport", "运动偏好");
        memory.setMemoryContent("PRIVATE_BODY");
        when(store.loadUserMemoryIndex(USER_ID)).thenReturn(List.of(memory));
        var result = new ListMemoriesTool(service).execute("{}");
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains(refs.toolContext().getTargets().getFirst().getMemoryRef()));
        assertFalse(result.getContent().contains("900123"));
        assertFalse(result.getContent().contains("PRIVATE_BODY"));
    }

    // 每个输入场景单独建立用户消息和引用表。
    private void begin(String input) { refs.beginRun(USER_ID, SESSION_ID, input); }

    // 固定合法字段，让测试只改变当前要验证的用户依据。
    private String createArguments(String evidence) {
        return json.writeValueAsString(Map.of("scope", "USER", "memoryKey", "sport", "memoryTopic", "运动",
                "memorySummary", "喜欢篮球", "memoryContent", "喜欢篮球", "userEvidence", evidence));
    }

    // 删除只使用服务端已分配的引用和完整用户原文。
    private String deletion(List<String> targets) {
        return json.writeValueAsString(Map.of("targetMemoryRefs", targets, "userEvidence", "请删除运动偏好"));
    }
}
