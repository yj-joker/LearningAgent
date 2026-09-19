package com.yjjoker.learningagent.service.impl;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ErrorCode;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.Status;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Milvus 向量服务测试")
@ExtendWith(MockitoExtension.class)
class MilvusServiceImplTest {

    @Mock
    private MilvusServiceClient milvusClient;

    @InjectMocks
    private MilvusServiceImpl milvusService;

    @Test
    @DisplayName("应去重切片 ID 并生成精确的主键删除表达式")
    void shouldDeleteVectorsByUniqueChunkIds() {
        MutationResult mutationResult = MutationResult.newBuilder()
                .setStatus(Status.newBuilder().setErrorCode(ErrorCode.Success).build())
                .setDeleteCnt(2)
                .build();
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(R.success(mutationResult));

        int deletedCount = milvusService.deleteByIds(List.of(101L, 102L, 101L));

        ArgumentCaptor<DeleteParam> deleteParamCaptor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient).delete(deleteParamCaptor.capture());
        assertEquals(2, deletedCount);
        assertEquals("id in [101,102]", deleteParamCaptor.getValue().getExpr());
    }
}
