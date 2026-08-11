package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.KnowledgePointsDTO;
import com.yjjoker.learningagent.entity.KnowledgePoints;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.repository.KnowledgePointsRepository;
import com.yjjoker.learningagent.service.ChaptersService;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.ChaptersVO;
import com.yjjoker.learningagent.vo.KnowledgePointsVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识点服务测试")
@ExtendWith(MockitoExtension.class)
class KnowledgePointsServiceImplTest {
    private static final long COURSE_ID = 100L;
    private static final long CHAPTER_ID = 200L;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private ChaptersService chaptersService;

    @Mock
    private KnowledgePointsRepository knowledgePointsRepository;

    @Mock
    private CoursesService coursesService;

    @InjectMocks
    private KnowledgePointsServiceImpl knowledgePointsService;

    @Test
    @DisplayName("创建时应生成雪花 ID 并交给 Repository 保存")
    void shouldGenerateIdWhenCreatingKnowledgePoint() {
        KnowledgePointsDTO dto = knowledgePointDTO(null, "JVM 内存结构");
        when(chaptersService.getChaptersByIds(anyList())).thenReturn(List.of(chapterVO()));
        when(snowflakeIdGenerator.nextId()).thenReturn(9001L);
        when(knowledgePointsRepository.saveAll(anyList())).thenReturn(1);

        List<KnowledgePointsVO> result = knowledgePointsService.createKnowledgePoint(List.of(dto));

        ArgumentCaptor<List<KnowledgePoints>> captor = knowledgePointsCaptor();
        verify(knowledgePointsRepository).saveAll(captor.capture());
        assertEquals(9001L, captor.getValue().getFirst().getId());
        assertEquals(9001L, result.getFirst().getId());
    }

    @Test
    @DisplayName("更新时应保留 DTO 中的已有 ID，不应生成新 ID")
    void shouldKeepExistingIdWhenUpdatingKnowledgePoint() {
        KnowledgePointsDTO dto = knowledgePointDTO(3001L, "JVM 内存模型");
        when(knowledgePointsRepository.findByIds(anyList())).thenReturn(List.of(knowledgePoint(3001L)));
        when(chaptersService.getChaptersByIds(anyList())).thenReturn(List.of(chapterVO()));
        when(knowledgePointsRepository.updateAll(anyList())).thenReturn(1);

        knowledgePointsService.updateKnowledgePoint(List.of(dto));

        ArgumentCaptor<List<KnowledgePoints>> captor = knowledgePointsCaptor();
        verify(knowledgePointsRepository).updateAll(captor.capture());
        assertEquals(3001L, captor.getValue().getFirst().getId());
        verify(snowflakeIdGenerator, never()).nextId();
    }

    @Test
    @DisplayName("一次更新中重复的知识点 ID 应在访问数据库前被拒绝")
    void shouldRejectDuplicateUpdateIds() {
        List<KnowledgePointsDTO> request = List.of(
                knowledgePointDTO(3001L, "堆"),
                knowledgePointDTO(3001L, "栈")
        );

        DataIllegalException exception = assertThrows(
                DataIllegalException.class,
                () -> knowledgePointsService.updateKnowledgePoint(request)
        );

        assertEquals("同一个知识点不能在一次请求中重复更新", exception.getMessage());
        verify(knowledgePointsRepository, never()).updateAll(anyList());
    }

    @Test
    @DisplayName("查询 ID 集合中的 null 应转换成统一的业务参数异常")
    void shouldRejectNullQueryId() {
        DataIllegalException exception = assertThrows(
                DataIllegalException.class,
                () -> knowledgePointsService.getKnowledgePointByIds(java.util.Arrays.asList(1L, null))
        );

        assertEquals("知识点 ID 不能为空且不能重复", exception.getMessage());
        verify(knowledgePointsRepository, never()).findByIds(anyList());
    }

    @Test
    @DisplayName("知识点名称唯一索引冲突应返回对应提示")
    void shouldTranslateChapterNameUniqueIndex() {
        KnowledgePointsDTO dto = knowledgePointDTO(null, "重复名称");
        when(chaptersService.getChaptersByIds(anyList())).thenReturn(List.of(chapterVO()));
        when(snowflakeIdGenerator.nextId()).thenReturn(9001L);
        when(knowledgePointsRepository.saveAll(anyList())).thenThrow(new DuplicateKeyException(
                "duplicate key",
                new SQLException("Duplicate entry for key 'uk_knowledge_points_chapter_name'")
        ));

        DataIllegalException exception = assertThrows(
                DataIllegalException.class,
                () -> knowledgePointsService.createKnowledgePoint(List.of(dto))
        );

        assertEquals("同一章节中不能存在同名知识点", exception.getMessage());
    }

    @Test
    @DisplayName("按 ID 查询知识点时应校验数据库真实课程的读取权限")
    void shouldCheckViewPermissionForFoundKnowledgePoints() {
        when(knowledgePointsRepository.findByIds(List.of(3001L)))
                .thenReturn(List.of(knowledgePoint(3001L)));

        knowledgePointsService.getKnowledgePointByIds(List.of(3001L));

        verify(coursesService).checkUserCanViewCourses(Set.of(COURSE_ID));
    }

    @Test
    @DisplayName("删除知识点时应根据数据库真实课程校验所有权")
    void shouldCheckOwnershipBeforeDeletingKnowledgePoints() {
        when(knowledgePointsRepository.findByIds(List.of(3001L)))
                .thenReturn(List.of(knowledgePoint(3001L)));
        when(knowledgePointsRepository.deleteAll(List.of(3001L))).thenReturn(1);

        knowledgePointsService.deleteKnowledgePoint(List.of(3001L));

        verify(coursesService).checkUserOwnsCourses(Set.of(COURSE_ID));
        verify(knowledgePointsRepository).deleteAll(List.of(3001L));
    }

    private KnowledgePointsDTO knowledgePointDTO(Long id, String name) {
        KnowledgePointsDTO dto = new KnowledgePointsDTO();
        dto.setId(id);
        dto.setCourseId(COURSE_ID);
        dto.setChapterId(CHAPTER_ID);
        dto.setName(name);
        dto.setSortOrder(1000L);
        dto.setDescription("description");
        return dto;
    }

    private KnowledgePoints knowledgePoint(Long id) {
        KnowledgePoints knowledgePoint = new KnowledgePoints();
        knowledgePoint.setId(id);
        knowledgePoint.setCourseId(COURSE_ID);
        knowledgePoint.setChapterId(CHAPTER_ID);
        knowledgePoint.setName("JVM");
        knowledgePoint.setSortOrder(1000L);
        return knowledgePoint;
    }

    private ChaptersVO chapterVO() {
        ChaptersVO chapter = new ChaptersVO();
        chapter.setId(CHAPTER_ID);
        chapter.setCourseId(COURSE_ID);
        return chapter;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<KnowledgePoints>> knowledgePointsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
