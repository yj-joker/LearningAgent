package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.dto.KnowledgePointsDTO;
import com.yjjoker.learningagent.vo.KnowledgePointsVO;

import java.util.List;

public interface KnowledgePointsService {
    // 创建知识点
    List<KnowledgePointsVO> createKnowledgePoint(List<KnowledgePointsDTO> knowledgePointsDTOList);

    // 根据id获取知识点
    List<KnowledgePointsVO> getKnowledgePointByIds(List<Long> ids);

    // 根据章节id获取知识点列表
    List<KnowledgePointsVO> getKnowledgePointsByChapterId(Long chapterId);

    // 更新知识点
    List<KnowledgePointsVO> updateKnowledgePoint(List<KnowledgePointsDTO> knowledgePointsDTOList);

    // 删除知识点
    void deleteKnowledgePoint(List<Long> ids);

    //根据课程id，找到该课程所有的对应的易混淆知识点
    List<KnowledgePointsVO> getConfusableKnowledgePointsByCourseId(Long courseId);

    //根据课程id，找到该课程所有的对应的前置知识点
    List<KnowledgePointsVO> getPrerequisiteKnowledgePointsByCourseId(Long courseId);

}
