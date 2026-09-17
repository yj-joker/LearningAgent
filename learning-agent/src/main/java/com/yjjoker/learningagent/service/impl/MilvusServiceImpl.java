package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.config.MilvusCollectionInitializer;
import com.yjjoker.learningagent.entity.DocumentChunks;
import com.yjjoker.learningagent.entity.EmbeddingResult;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import com.yjjoker.learningagent.service.MilvusService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ErrorCode;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@AllArgsConstructor
public class MilvusServiceImpl implements MilvusService {
    // 必须与 MilvusCollectionInitializer 中 embedding 字段的维度一致。
    private static final int VECTOR_DIMENSION = 1536;

    private final MilvusServiceClient milvusClient;

    // 调用方按 documentChunks 的顺序提取文本并向量化，这里负责批量写入已有结果。
    // 集合必须提前创建；id 直接使用切片 ID，不生成新 ID，也不依赖 Milvus 自动生成。
    @Override
    public int insertAll(List<DocumentChunks> documentChunks, List<EmbeddingResult> embeddingResults) {
        if (documentChunks == null || embeddingResults == null) {
            throw new LearningAgentServiceException("切片列表和向量结果列表不能为空");
        }
        int count = documentChunks.size();
        if (embeddingResults.size() != count) {
            throw new LearningAgentServiceException("切片数量与向量数量不一致");
        }
        if (count == 0) {
            return 0;
        }

        //收集每行的切片 ID 和文档 ID，避免空 ID 或批次内重复 ID 写入。
        List<Long> ids = new ArrayList<>(count);
        List<Long> documentIds = new ArrayList<>(count);
        Set<Long> uniqueIds = new HashSet<>();
        for (DocumentChunks chunk : documentChunks) {
            if (chunk == null || chunk.getId() == null || chunk.getDocumentId() == null) {
                throw new LearningAgentServiceException("切片及其 ID、文档 ID 不能为空，请先为切片赋予 ID");
            }
            if (!uniqueIds.add(chunk.getId())) {
                throw new LearningAgentServiceException("当前批次存在重复的切片 ID：" + chunk.getId());
            }
            ids.add(chunk.getId());
            documentIds.add(chunk.getDocumentId());
        }

        // 第二步：按 API 返回的 index 对齐向量。index 是本次请求中的文本下标，
        // 从 0 开始，不是文档全局的 chunkIndex，也不是切片 ID。
        EmbeddingResult[] orderedResults = new EmbeddingResult[count];
        for (EmbeddingResult result : embeddingResults) {
            if (result == null || result.index() < 0 || result.index() >= count) {
                throw new LearningAgentServiceException("向量结果包含无效的文本下标");
            }
            if (orderedResults[result.index()] != null) {
                throw new LearningAgentServiceException("向量结果包含重复的文本下标：" + result.index());
            }
            orderedResults[result.index()] = result;
        }

        // 第三步：校验维度，将 API 的 Double 数组转换为 FloatVector 所需的 Float 数组。
        List<List<Float>> vectors = new ArrayList<>(count);
        for (EmbeddingResult result : orderedResults) {
            if (result == null || result.embedding() == null
                    || result.embedding().size() != VECTOR_DIMENSION) {
                throw new LearningAgentServiceException("每条切片向量必须为 " + VECTOR_DIMENSION + " 维");
            }
            List<Float> vector = new ArrayList<>(VECTOR_DIMENSION);
            for (Double value : result.embedding()) {
                if (value == null || !Double.isFinite(value) || !Float.isFinite(value.floatValue())) {
                    throw new LearningAgentServiceException("向量包含空值、非有限值或超出 Float 范围的数值");
                }
                vector.add(value.floatValue());
            }
            vectors.add(vector);
        }

        //组织数据
        InsertParam param = InsertParam.newBuilder()
                .withCollectionName(MilvusCollectionInitializer.COLLECTION_NAME)
                .withFields(List.of(
                        new InsertParam.Field("id", ids),
                        new InsertParam.Field("document_id", documentIds),
                        new InsertParam.Field("chunk_id", ids),
                        new InsertParam.Field("embedding", vectors)
                ))
                .build();

        //一次请求插入整批数据。
        R<MutationResult> response = milvusClient.insert(param);
        if (response == null) {
            throw new LearningAgentServiceException("Milvus 批量插入失败：未返回结果");
        }
        if (!Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            throw new LearningAgentServiceException(
                    "Milvus 批量插入失败：" + response.getMessage(), response.getException());
        }
        MutationResult result = response.getData();
        if (result == null || !result.hasStatus()
                || result.getStatus().getErrorCode() != ErrorCode.Success
                || result.getErrIndexCount() != 0 || result.getInsertCnt() != count) {
            throw new LearningAgentServiceException("Milvus 未成功写入完整批次，请核查该批切片的向量记录");
        }

        // 只有确认整批写入成功，才回填内存对象；这里不会自动更新 MySQL。
        // 抛出异常也不会撤销 Milvus 中可能已写入的记录，调用方需要处理补偿。
        for (DocumentChunks chunk : documentChunks) {
            chunk.setVectorId(String.valueOf(chunk.getId()));
        }
        return Math.toIntExact(result.getInsertCnt());
    }

}
