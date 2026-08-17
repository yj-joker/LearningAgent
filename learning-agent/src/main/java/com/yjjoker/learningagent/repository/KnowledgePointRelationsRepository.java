package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.KnowledgePointRelations;
import com.yjjoker.learningagent.entity.KnowledgePoints;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgePointRelationsRepository {

    // 批量保存知识点关系，并将数据库生成的主键回填到实体。
    @Insert("INSERT INTO knowledge_point_relations" +
            " (id, from_point_id, to_point_id, relation_type, status,source,created_by,reviewed_by,reviewed_at,created_at)" +
            " VALUES (#{relations.id}, #{relations.fromPointId}, #{relations.toPointId}, #{relations.relationType}," +
            " #{relations.status},#{relations.source},#{relations.createdBy}," +
            "#{relations.reviewedBy},#{relations.reviewedAt},#{relations.createdAt})")
    int save(@Param("relations") KnowledgePointRelations relations);

    // 根据关系 ID 列表批量查询知识点关系。
    List<KnowledgePointRelations> findByIds(@Param("ids") List<Long> ids);

    // 查询指定知识点作为起点或终点参与的全部关系。
    List<KnowledgePointRelations> findByKnowledgePointId(@Param("knowledgePointId") Long knowledgePointId);

    // 根据关系 ID 使用一条 CASE WHEN SQL 批量更新知识点关系。
    int updateAll(@Param("relations") List<KnowledgePointRelations> relations);

    // 根据关系 ID 列表批量删除知识点关系。
    int deleteAll(@Param("ids") List<Long> ids);

    // 环检测
    // 如果期望A->B，那么就不期望出现从B出发，然后回到A的情况：也就是B->C->D....->A
    //所以查询从B出发，然后找B的所有后置及其后置ToId，看是否能到达A
    @Select(value = """
            WITH RECURSIVE reachable AS (
                SELECT to_point_id AS node_id
                FROM knowledge_point_relations
                WHERE from_point_id = #{toId}
                  AND relation_type = 'PREREQUISITE'
                  AND status = 'ACTIVE'
                UNION
                SELECT r.to_point_id
                FROM knowledge_point_relations r
                INNER JOIN reachable rc ON r.from_point_id = rc.node_id
                WHERE r.relation_type = 'PREREQUISITE'
                  AND r.status = 'ACTIVE'
            )
            SELECT COUNT(*) > 0 FROM reachable WHERE node_id = #{fromId}""")
    boolean ringDetection(@Param("fromId") Long fromId, @Param("toId") Long toId);


}

