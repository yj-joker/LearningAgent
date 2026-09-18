package com.yjjoker.learningagent.repository;

import com.yjjoker.learningagent.entity.DocumentTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DocumentTaskRepository {

    // 创建一个待调度的文档处理任务。
    @Insert("INSERT INTO document_tasks " +
            "(document_id, user_id, task_type, status, retry_count, max_retries, created_at, updated_at) " +
            "VALUES (#{task.documentId}, #{task.userId}, #{task.taskType}, #{task.status}, 0, " +
            "#{task.maxRetries}, #{task.createdAt}, #{task.updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "task.id", keyColumn = "id")
    int save(@Param("task") DocumentTask task);

    // 按创建顺序取出有限数量的待处理任务，避免一次加载大量任务。
    @Select("SELECT * FROM document_tasks WHERE status = 'PENDING' " +
            "ORDER BY created_at, id LIMIT #{limit}")
    List<DocumentTask> findPendingTasks(@Param("limit") int limit);

    // 原子认领任务：只有仍为 PENDING 的任务才能被当前调度器改为 RUNNING。
    @Update("UPDATE document_tasks SET status = 'RUNNING', started_at = #{startedAt}, " +
            "completed_at = NULL, updated_at = #{startedAt} " +
            "WHERE id = #{taskId} AND status = 'PENDING'")
    int claimTask(@Param("taskId") Long taskId, @Param("startedAt") LocalDateTime startedAt);

    // 任务成功后更新完成时间，避免已结束任务被再次调度。
    @Update("UPDATE document_tasks SET status = 'SUCCESS', completed_at = #{completedAt}, " +
            "error_message = NULL, updated_at = #{completedAt} " +
            "WHERE id = #{taskId} AND status = 'RUNNING'")
    int markSuccess(@Param("taskId") Long taskId, @Param("completedAt") LocalDateTime completedAt);

    // 临时失败时增加重试次数；未达到上限回到 PENDING，达到上限进入 FAILED。
    @Update("UPDATE document_tasks SET retry_count = retry_count + 1, " +
            "status = CASE WHEN retry_count >= max_retries - 1 THEN 'FAILED' ELSE 'PENDING' END, " +
            "error_message = #{errorMessage}, " +
            "completed_at = CASE WHEN retry_count >= max_retries - 1 THEN #{updatedAt} ELSE NULL END, " +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{taskId} AND status = 'RUNNING'")
    int markFailureOrRetry(@Param("taskId") Long taskId,
                           @Param("errorMessage") String errorMessage,
                           @Param("updatedAt") LocalDateTime updatedAt);

    // 线程池拒绝任务时，把已经认领的任务放回待处理状态。
    @Update("UPDATE document_tasks SET status = 'PENDING', started_at = NULL, " +
            "updated_at = #{updatedAt}, error_message = #{errorMessage} " +
            "WHERE id = #{taskId} AND status = 'RUNNING'")
    int requeueTask(@Param("taskId") Long taskId,
                    @Param("errorMessage") String errorMessage,
                    @Param("updatedAt") LocalDateTime updatedAt);

    // 应用启动时恢复上一次实例遗留的 RUNNING 任务。
    @Update("UPDATE document_tasks SET status = 'PENDING', started_at = NULL, " +
            "updated_at = #{updatedAt} WHERE status = 'RUNNING'")
    int resetRunningTasksToPending(@Param("updatedAt") LocalDateTime updatedAt);

    // 异步线程根据任务 ID 读取最新任务状态。
    @Select("SELECT * FROM document_tasks WHERE id = #{taskId}")
    DocumentTask getById(@Param("taskId") Long taskId);
}
