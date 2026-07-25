package com.yjjoker.learningagent.repository.test.impl;

import com.yjjoker.learningagent.entity.Courses;
import com.yjjoker.learningagent.projectenum.CoursesTypeEnum;
import com.yjjoker.learningagent.repository.CoursesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Slf4j
public class CoursesRepositoryTestImpl implements CoursesRepository {
    private static final Map<Long, Courses> coursesMap = new HashMap<>();
    public CoursesRepositoryTestImpl(List<Long> ids, List<Long> userIds, List<CoursesTypeEnum>coursesTypeEnums ) {
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            Long userId = userIds.get(i);
            CoursesTypeEnum courseType = coursesTypeEnums.get(i);
            Courses courses = new Courses();
            courses.setId(id);
            courses.setUserId(userId);
            courses.setPublisherId(userId);
            courses.setCourseType(courseType);
            coursesMap.put(id, courses);
        }
        log.info("初始化课程数据成功");
    }
    @Override
    public Optional<Courses> findCourseById(Long id) {
        Courses courses = coursesMap.get(id);
        if(courses==null){
            return Optional.empty();
        }
        return Optional.of(courses);
    }
}
