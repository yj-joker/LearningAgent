package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.ChaptersDTO;
import com.yjjoker.learningagent.entity.Chapters;
import com.yjjoker.learningagent.exception.DataIllegalException;
import com.yjjoker.learningagent.exception.NotFountException;
import com.yjjoker.learningagent.repository.ChaptersRepository;
import com.yjjoker.learningagent.repository.KnowledgePointsRepository;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import com.yjjoker.learningagent.vo.ChaptersVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("章节服务测试")
@ExtendWith(MockitoExtension.class)
class ChaptersServiceImplTest {
    @Mock
    private ChaptersRepository chaptersRepository;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private CoursesService coursesService;

    @Mock
    private KnowledgePointsRepository knowledgePointsRepository;

    @InjectMocks
    private ChaptersServiceImpl chaptersService;

    @Test
    @DisplayName("创建章节时应先校验课程所有权，再生成雪花 ID")
    void shouldCheckOwnershipAndGenerateIdWhenCreating() {
        ChaptersDTO request = chapterDTO(null, 100L);
        when(snowflakeIdGenerator.nextId()).thenReturn(9001L);
        when(chaptersRepository.saveAll(anyList())).thenReturn(1);

        List<ChaptersVO> result = chaptersService.createChapters(List.of(request));

        verify(coursesService).checkUserOwnsCourse(100L);
        verify(snowflakeIdGenerator).nextId();
        assertEquals(9001L, result.getFirst().getId());
    }

    @Test
    @DisplayName("批量创建不同课程的章节时应在生成 ID 前拒绝")
    void shouldRejectMixedCoursesBeforeGeneratingIds() {
        List<ChaptersDTO> request = List.of(
                chapterDTO(null, 100L),
                chapterDTO(null, 200L)
        );

        DataIllegalException exception = assertThrows(
                DataIllegalException.class,
                () -> chaptersService.createChapters(request)
        );

        assertEquals("批量创建的章节必须属于同一课程", exception.getMessage());
        verify(snowflakeIdGenerator, never()).nextId();
        verify(chaptersRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("按课程查询章节时应使用读取权限而不是所有权")
    void shouldCheckViewPermissionWhenQueryingByCourseId() {
        when(chaptersRepository.getChaptersByCourseId(100L)).thenReturn(List.of(chapter(1L, 100L)));

        List<ChaptersVO> result = chaptersService.getChaptersByCourseId(100L);

        verify(coursesService).checkUserCanViewCourse(100L);
        assertEquals(1L, result.getFirst().getId());
        assertEquals(100L, result.getFirst().getCourseId());
    }

    @Test
    @DisplayName("按 ID 批量查询章节时应一次校验所有实际课程")
    void shouldCheckAllActualCoursesWhenQueryingByIds() {
        when(chaptersRepository.getChaptersByIds(List.of(1L, 2L)))
                .thenReturn(List.of(chapter(1L, 100L), chapter(2L, 200L)));

        List<ChaptersVO> result = chaptersService.getChaptersByIds(List.of(1L, 2L));

        verify(coursesService).checkUserCanViewCourses(Set.of(100L, 200L));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("普通批量查询允许不存在的章节 ID 返回部分结果")
    void shouldReturnPartialResultWhenSomeChapterIdsDoNotExist() {
        when(chaptersRepository.getChaptersByIds(List.of(1L, 999L)))
                .thenReturn(List.of(chapter(1L, 100L)));

        List<ChaptersVO> result = chaptersService.getChaptersByIds(List.of(1L, 999L));

        verify(coursesService).checkUserCanViewCourses(Set.of(100L));
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("更新章节时应根据数据库真实课程校验所有权")
    void shouldCheckActualCourseOwnershipWhenUpdating() {
        ChaptersDTO request = chapterDTO(1L, 100L);
        when(chaptersRepository.getChaptersByIds(List.of(1L)))
                .thenReturn(List.of(chapter(1L, 100L)));
        when(chaptersRepository.updateAll(anyList())).thenReturn(1);

        List<ChaptersVO> result = chaptersService.updateChapters(List.of(request));

        verify(coursesService).checkUserOwnsCourse(100L);
        verify(chaptersRepository).updateAll(anyList());
        assertEquals(1L, result.getFirst().getId());
    }

    @Test
    @DisplayName("更新章节时应拒绝前端伪造的课程 ID")
    void shouldRejectForgedCourseIdWhenUpdating() {
        ChaptersDTO request = chapterDTO(1L, 200L);
        when(chaptersRepository.getChaptersByIds(List.of(1L)))
                .thenReturn(List.of(chapter(1L, 100L)));

        DataIllegalException exception = assertThrows(
                DataIllegalException.class,
                () -> chaptersService.updateChapters(List.of(request))
        );

        assertEquals("章节 ID 与课程 ID 不匹配", exception.getMessage());
        verify(coursesService).checkUserOwnsCourse(100L);
        verify(chaptersRepository, never()).updateAll(anyList());
    }

    @Test
    @DisplayName("删除章节时任意 ID 不存在都应整批失败")
    void shouldRejectDeleteWhenAnyChapterDoesNotExist() {
        when(chaptersRepository.getChaptersByIds(List.of(1L, 999L)))
                .thenReturn(List.of(chapter(1L, 100L)));

        assertThrows(
                NotFountException.class,
                () -> chaptersService.deleteChaptersByIds(List.of(1L, 999L))
        );

        verify(chaptersRepository, never()).deleteChaptersByIds(anyList());
    }

    @Test
    @DisplayName("删除章节时应根据数据库真实课程校验所有权")
    void shouldCheckActualCourseOwnershipWhenDeleting() {
        when(chaptersRepository.getChaptersByIds(List.of(1L)))
                .thenReturn(List.of(chapter(1L, 100L)));
        when(chaptersRepository.deleteChaptersByIds(List.of(1L))).thenReturn(1);

        chaptersService.deleteChaptersByIds(List.of(1L));

        verify(coursesService).checkUserOwnsCourse(100L);
        verify(chaptersRepository).deleteChaptersByIds(List.of(1L));
    }

    private Chapters chapter(Long id, Long courseId) {
        Chapters chapter = new Chapters();
        chapter.setId(id);
        chapter.setCourseId(courseId);
        chapter.setTitle("chapter-" + id);
        chapter.setSortOrder(id * 1000);
        return chapter;
    }

    private ChaptersDTO chapterDTO(Long id, Long courseId) {
        ChaptersDTO chapter = new ChaptersDTO();
        chapter.setId(id);
        chapter.setCourseId(courseId);
        chapter.setTitle("updated chapter");
        chapter.setSortOrder(1000L);
        return chapter;
    }
}
