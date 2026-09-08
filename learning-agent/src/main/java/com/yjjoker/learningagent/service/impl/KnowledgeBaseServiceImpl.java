package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.dto.KnowledgeBaseDTO;
import com.yjjoker.learningagent.entity.KnowledgeBase;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.exception.NullException;
import com.yjjoker.learningagent.exception.ViolationOperationException;
import com.yjjoker.learningagent.projectenum.OwnerType;
import com.yjjoker.learningagent.projectenum.UserRoleEnum;
import com.yjjoker.learningagent.projectenum.VisibilityEnum;
import com.yjjoker.learningagent.repository.KnowledgeBaseRepository;
import com.yjjoker.learningagent.service.CoursesService;
import com.yjjoker.learningagent.service.KnowledgeBaseService;
import com.yjjoker.learningagent.utils.BaseContext;
import com.yjjoker.learningagent.vo.KnowledgeBaseVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
@Slf4j
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final CoursesService coursesService;

    //添加知识库
    @Override
    public KnowledgeBaseVO addKnowledgeBase(KnowledgeBaseDTO knowledgeBaseDTO) {
        if (knowledgeBaseDTO == null) {
            log.error("知识库DTO为空");
            throw new NullException("知识库错误");
        }
        //检查课程id是否合法
        UserRoleEnum userRole = BaseContext.getCurrentRole();
        if (userRole == null) {
            log.error("用户角色为空");
            throw new ViolationOperationException("违规操作");
        }
        if(userRole != UserRoleEnum.ADMIN){
            coursesService.checkUserOwnsCourse(knowledgeBaseDTO.getCourseId());
        } else {
            // 管理员可以为任意课程创建系统知识库，但课程本身必须真实存在。
            coursesService.findCourse(knowledgeBaseDTO.getCourseId());
        }
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setCourseId(knowledgeBaseDTO.getCourseId());
        knowledgeBase.setName(knowledgeBaseDTO.getName());
        knowledgeBase.setDescription(knowledgeBaseDTO.getDescription());
        if(userRole == UserRoleEnum.ADMIN){
            knowledgeBase.setOwnerType(OwnerType.SYSTEM);
            knowledgeBase.setVisibility(VisibilityEnum.PUBLIC);
        }else{
            knowledgeBase.setOwnerType(OwnerType.USER);
            knowledgeBase.setVisibility(VisibilityEnum.PRIVATE);
        }
        knowledgeBase.setCreatedAt(LocalDateTime.now());
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        //保存知识库
        int result;
        try {
            result = knowledgeBaseRepository.save(knowledgeBase);
        } catch (Exception e) {
            log.error("数据库保存知识库失败", e);
            throw new LearningAgentServiceException("数据库保存知识库失败");
        }
        if (result != 1) {
            log.error("数据库保存知识库失败");
            throw new LearningAgentServiceException("数据库保存知识库失败");
        }
        log.info("保存知识库成功");
        KnowledgeBaseVO knowledgeBaseVO = new KnowledgeBaseVO();
        BeanUtils.copyProperties(knowledgeBase, knowledgeBaseVO);
        return knowledgeBaseVO;
    }
}
