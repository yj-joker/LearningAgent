package com.yjjoker.learningagent.service.impl;

import com.alibaba.fastjson.JSON;
import com.yjjoker.learningagent.client.AliyunEmbeddingClient;
import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.entity.DocumentChunksMetadata;
import com.yjjoker.learningagent.entity.EmbeddingResult;
import com.yjjoker.learningagent.repository.DocumentChunksRepository;
import com.yjjoker.learningagent.service.MilvusService;
import com.yjjoker.learningagent.utils.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@AllArgsConstructor
public class DocumentParsePersistenceService {
    private static final int CHUNK_SIZE = 500;
    private static final int VECTOR_BATCH_SIZE = 10;
    private static final int MAX_DATABASE_ATTEMPTS = 4;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 200L;
    private static final long MAX_RETRY_DELAY_MILLIS = 2000L;

    private final DocumentChunksRepository documentChunksRepository;
    private final AliyunEmbeddingClient aliyunEmbeddingClient;
    private final MilvusService milvusService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final DocumentChunkTransactionService documentChunkTransactionService;

    // 完成文本切片、向量化、向量写入和 MySQL 状态切换的整体编排。
    // 当前方法故意不加事务，避免阿里云和 Milvus 的网络耗时延长 MySQL 锁的持有时间。
    public int replaceChunksAndMarkReady(Long documentId, String text) {
        if (documentId == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("文档 ID 和待解析文本不能为空");
        }

        // 在替换前记录旧切片 ID，数据库提交成功后只删除这些旧向量。
        // TODO 同一文档如果未来允许多个解析任务并行，需要增加 parseGeneration 隔离不同版本。
        List<Long> oldChunkIds = documentChunksRepository.findIdsByDocumentId(documentId);

        // 只生成一次切片和雪花 ID，后续死锁重试继续复用同一组对象，避免产生新的向量主键。
        List<DocumentChunks> chunks = buildChunks(documentId, text);
        List<Long> newChunkIds = chunks.stream()
                .map(DocumentChunks::getId)
                .toList();

        try {
            // 先在 MySQL 事务外调用阿里云和 Milvus，远程调用期间不持有数据库锁。
            embedAndSaveVectors(chunks);
            // 远程步骤完成后，只对删除、批量插入和 READY 状态更新执行短事务及有限重试。
            replaceDatabaseStateWithRetry(documentId, chunks);
        } catch (RuntimeException exception) {
            // MySQL 尚未成功提交时，只补偿删除本次新 ID 对应的向量，绝不能按 documentId 清空旧数据。
            deleteVectorsQuietly(newChunkIds, "清理本次失败解析写入的新向量", exception);
            throw exception;
        }

        // MySQL 已经切换到新切片后，再清理替换前的旧向量；失败时保留主流程成功结果并记录日志。
        deleteVectorsQuietly(oldChunkIds, "清理替换前的旧向量", null);
        return chunks.size();
    }

    // 按固定长度创建全部切片，并提前生成 MySQL 与 Milvus 共用的稳定主键。
    // 提前生成 ID 可以保证数据库重试不会重复调用雪花算法，也不会改变已经写入 Milvus 的向量 ID。
    private List<DocumentChunks> buildChunks(Long documentId, String text) {
        int estimatedSize = (text.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        List<DocumentChunks> chunks = new ArrayList<>(estimatedSize);
        LocalDateTime createdAt = LocalDateTime.now();

        for (int start = 0, chunkIndex = 0; start < text.length(); start += CHUNK_SIZE, chunkIndex++) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            long chunkId = snowflakeIdGenerator.nextId();

            DocumentChunks chunk = new DocumentChunks();
            chunk.setId(chunkId);
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setContent(text.substring(start, end));
            chunk.setVectorId(String.valueOf(chunkId));
            chunk.setCreatedAt(createdAt);

            // metadata 保存检索结果需要的来源信息，内容与当前切片的文档 ID 和序号保持一致。
            DocumentChunksMetadata metadata = new DocumentChunksMetadata();
            metadata.setDocumentId(documentId);
            metadata.setChunkIndex(chunkIndex);
            chunk.setMetadata(JSON.toJSONString(metadata));
            chunks.add(chunk);
        }
        return chunks;
    }

    // 分批调用阿里云生成向量，再把同一批结果写入 Milvus。
    // 每批完成后即可释放该批向量对象，避免在内存中同时保存整份文档的所有 embedding。
    private void embedAndSaveVectors(List<DocumentChunks> chunks) {
        for (int start = 0; start < chunks.size(); start += VECTOR_BATCH_SIZE) {
            int end = Math.min(start + VECTOR_BATCH_SIZE, chunks.size());
            List<DocumentChunks> batch = chunks.subList(start, end);
            List<String> texts = batch.stream()
                    .map(DocumentChunks::getContent)
                    .toList();

            // 调用阿里云时只传当前批次文本，返回结果的 index 由 MilvusService 按批次顺序对齐。
            List<EmbeddingResult> embeddingResults = aliyunEmbeddingClient.embedDocuments(texts);
            // 阿里云成功后立即写入同一批向量；任何异常都会进入外层的精确补偿清理。
            milvusService.insertAll(batch, embeddingResults);
        }
    }

    // 只对 MySQL 短事务执行最多四次尝试，避免死锁时重复消耗阿里云和 Milvus 请求。
    private void replaceDatabaseStateWithRetry(Long documentId, List<DocumentChunks> chunks) {
        for (int attempt = 1; attempt <= MAX_DATABASE_ATTEMPTS; attempt++) {
            try {
                // 调用独立的 Spring Bean，使 @Transactional 能通过代理真正创建和提交事务。
                documentChunkTransactionService.replaceChunksAndMarkReady(documentId, chunks);
                return;
            } catch (RuntimeException exception) {
                boolean retryable = isRetryableLockFailure(exception);
                if (!retryable || attempt == MAX_DATABASE_ATTEMPTS) {
                    throw exception;
                }

                long delayMillis = calculateRetryDelayMillis(attempt);
                log.warn("文档切片短事务发生死锁或锁等待超时，将进行第 {} 次重试，documentId={}，delayMillis={}",
                        attempt + 1, documentId, delayMillis, exception);
                // 当前执行线程短暂退避后再进入下一次数据库事务，减少立即再次碰撞的概率。
                sleepBeforeRetry(delayMillis);
            }
        }
    }

    // 识别可以安全重试的数据库并发异常，业务校验、参数错误和其他数据库错误不会被掩盖。
    private boolean isRetryableLockFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            // Spring 会把常见的死锁和获取锁失败翻译为这个异常体系。
            if (current instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                int errorCode = sqlException.getErrorCode();
                String sqlState = sqlException.getSQLState();
                // 1213 表示死锁，1205 表示锁等待超时，40001 表示事务序列化冲突。
                if (errorCode == 1213 || errorCode == 1205 || "40001".equals(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    // 采用 200、400、800 毫秒级别的指数退避，并添加不超过基础等待时间的随机抖动。
    // 随机抖动可以避免多个失败线程在固定时间点同时醒来并再次争抢相同索引范围。
    private long calculateRetryDelayMillis(int failedAttempt) {
        long exponentialDelay = INITIAL_RETRY_DELAY_MILLIS << Math.max(0, failedAttempt - 1);
        long boundedDelay = Math.min(exponentialDelay, MAX_RETRY_DELAY_MILLIS);
        long jitter = ThreadLocalRandom.current().nextLong(boundedDelay + 1);
        return Math.min(boundedDelay + jitter, MAX_RETRY_DELAY_MILLIS);
    }

    // 执行重试等待；如果线程被要求中断，就恢复中断标记并停止继续重试。
    private void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文档切片数据库重试等待被中断", exception);
        }
    }

    // 按切片 ID 精确删除补偿向量，避免使用 documentId 误删已经存在或已经提交的新版本数据。
    private void deleteVectorsQuietly(List<Long> chunkIds, String operation, RuntimeException originalException) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try {
            milvusService.deleteByIds(chunkIds);
        } catch (RuntimeException cleanupException) {
            log.error("{}失败，chunkCount={}", operation, chunkIds.size(), cleanupException);
            if (originalException != null) {
                originalException.addSuppressed(cleanupException);
            }
        }
    }
}
