package com.yjjoker.learningagent.config;

import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.grpc.DataType;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.param.R;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.IndexDescription;
import io.milvus.param.collection.LoadCollectionParam;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
/**
 * 应用启动时准备 Milvus：集合结构 -> 向量索引配置 -> 加载集合。
 *
 * ensure 方法负责“检查后决定是否创建”，create 方法负责真正发送创建请求。
 * 原来的 createCollection、createIndex 仍被使用，不需要放入定时任务反复调用。
 * 空集合也可以声明索引配置；实际的 IVF 训练需要数据，由 Milvus 后台按数据段构建。
 * 数据段（segment）是 Milvus 管理一批数据的单位，不等同于 IVF 的聚类分组。
 */
@Component
@AllArgsConstructor
@Slf4j
public class MilvusCollectionInitializer implements ApplicationRunner {

    public static final String COLLECTION_NAME = "document_chunks";

    private final MilvusServiceClient milvusClient;

    // Spring 创建并注入 Bean（它管理的对象）后，自动调用
    @Override
    public void run(@NonNull ApplicationArguments args) {
        ensureCollection();
        ensureIndex();
        loadCollection();
        log.info("Milvus初始化完成");
    }

    /**
     * 检查 embedding 的索引配置，缺少时调用原来的 createIndex()。
     * “有索引配置”不代表所有数据段都已建完物理索引；后续数据的构建由 Milvus 管理。
     * 这里只检查字段、算法和度量；不把索引名称当作字段名称，也不盲取第一个索引。
     */
    public void ensureIndex() {
        R<DescribeIndexResponse> response;
        try {
            response = milvusClient.describeIndex(DescribeIndexParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME).build());
        } catch (Exception e) {
            throw new LearningAgentServiceException("检查 Milvus 索引失败", e);
        }
        // SDK 2.3.4 可能以 IndexNotExist 返回“没有索引”。仅这一明确状态允许创建；
        // 网络失败、权限不足等其他错误必须继续报错，不能当成不存在。
        if (response != null && Integer.valueOf(R.Status.IndexNotExist.getCode()).equals(response.getStatus())) {
            createIndex();
            return;
        }
        requireSuccess(response, "检查 Milvus 索引失败");
        if (response.getData() == null) {
            throw new LearningAgentServiceException("检查 Milvus 索引失败：响应数据为空");
        }
        // describeIndex 返回集合的索引描述，从中挑选 embedding 字段的索引。
        // 即使其他字段已有索引，也不能据此断言 embedding 已有索引。
        List<IndexDescription> indexes = response.getData().getIndexDescriptionsList().stream()
                .filter(index -> "embedding".equals(index.getFieldName())).toList();
        if (indexes.isEmpty()) {
            createIndex();
            return;
        }
        IndexDescription index = indexes.get(0);
        // params 是服务端返回的键值配置；缺少关键配置时使用空串，随后按不匹配报错。
        String indexType = index.getParamsList().stream()
                .filter(p -> "index_type".equals(p.getKey())).map(p -> p.getValue()).findFirst().orElse("");
        String metricType = index.getParamsList().stream()
                .filter(p -> "metric_type".equals(p.getKey())).map(p -> p.getValue()).findFirst().orElse("");
        if (!"embedding".equals(index.getFieldName()) || !"IVF_FLAT".equalsIgnoreCase(indexType)
                || !"COSINE".equalsIgnoreCase(metricType)) {
            throw new LearningAgentServiceException("已有 Milvus 索引配置不符合预期");
        }
        log.info("Milvus 索引已存在，跳过创建：{}", index.getIndexName());
    }

    /**
     * 查询节点是 Milvus 中执行搜索的服务组件，不是 IVF 的聚类中心。
     * load 让查询组件准备集合所需的数据和索引；不是从 MySQL 导入数据，也不生成向量。
     * 后续正常写入的数据由 Milvus 管理可见性，无须每插入一批就重新 load。
     */
    public void loadCollection() {
        R<?> response;
        try {
            response = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    // 显式等待加载完成，避免只提交了请求就记录“加载完成”。
                    .withSyncLoad(true).build());
        } catch (Exception e) {
            throw new LearningAgentServiceException("加载 Milvus 集合失败", e);
        }
        requireSuccess(response, "加载 Milvus 集合失败");
        log.info("Milvus 集合加载完成：{}", COLLECTION_NAME);
    }
    /** 启动检查：只有成功取得 false 才创建集合；true 则校验结构，检查失败直接报错。 */
    public void ensureCollection() {
        R<Boolean> existsResponse;
        try {
            existsResponse = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME).build());
        } catch (Exception e) {
            throw new LearningAgentServiceException("检查 Milvus 集合失败", e);
        }
        requireSuccess(existsResponse, "检查 Milvus 集合失败");
        // null 表示缺少检查结果，不能用“不是 true”推导为“不存在”。
        if (existsResponse.getData() == null) {
            throw new LearningAgentServiceException("检查 Milvus 集合失败：未返回存在性结果");
        }
        if (Boolean.TRUE.equals(existsResponse.getData())) {
            validateExistingCollection();
            log.info("集合已存在且结构符合预期：{}", COLLECTION_NAME);
            return;
        }

        // 到这里已经明确得到 false，复用原来的创建方法。
        createCollection();
        // 创建请求成功后，再读取服务端状态和结构，避免只凭请求提交成功就继续启动。
        R<Boolean> afterCreate;
        try {
            afterCreate = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME).build());
        } catch (Exception e) {
            throw new LearningAgentServiceException("创建集合后检查失败", e);
        }
        requireSuccess(afterCreate, "创建集合后检查失败");
        if (!Boolean.TRUE.equals(afterCreate.getData())) {
            throw new LearningAgentServiceException("Milvus 集合创建后仍不存在：" + COLLECTION_NAME);
        }
        validateExistingCollection();
    }

    // 读取实际 Schema（字段结构），与插入代码依赖的字段约定比较。
    // 不匹配时只报错，不自动删除重建集合，以免丢失已有向量。
    private void validateExistingCollection() {
        R<DescribeCollectionResponse> response;
        try {
            response = milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME).build());
        } catch (Exception e) {
            throw new LearningAgentServiceException("读取 Milvus 集合结构失败", e);
        }
        requireSuccess(response, "读取 Milvus 集合结构失败");
        DescribeCollectionResponse data = response.getData();
        if (data == null || !data.hasSchema()) {
            throw new LearningAgentServiceException("Milvus 集合结构为空：" + COLLECTION_NAME);
        }
        CollectionSchema schema = data.getSchema();
        // ID 由应用提供：这里使用 MySQL 切片 ID，不能开启 Milvus 自动生成 ID。
        if (schema.getAutoID()) {
            throw new LearningAgentServiceException("集合 autoID 必须为 false：" + COLLECTION_NAME);
        }
        FieldSchema id = findField(schema, "id");
        requireField(id, DataType.Int64, true, null);
        requireField(findField(schema, "document_id"), DataType.Int64, false, null);
        requireField(findField(schema, "chunk_id"), DataType.Int64, false, null);
        FieldSchema embedding = findField(schema, "embedding");
        requireField(embedding, DataType.FloatVector, false, "1536");
    }

    // 按字段名匹配，而不是依赖服务端返回字段的排列顺序。
    private FieldSchema findField(CollectionSchema schema, String name) {
        return schema.getFieldsList().stream().filter(field -> name.equals(field.getName()))
                .findFirst().orElseThrow(() -> new LearningAgentServiceException("集合缺少字段：" + name));
    }

    // dimension 为 null 表示普通整数列；向量列则必须存在 dim 参数且值符合预期。
    // 类型、主键标记、autoID 任一不符，当前插入代码就无法按约定使用该集合。
    private void requireField(FieldSchema field, DataType type, boolean primaryKey, String dimension) {
        if (field.getDataType() != type || field.getIsPrimaryKey() != primaryKey || field.getAutoID()) {
            throw new LearningAgentServiceException("字段结构不符合预期：" + field.getName());
        }
        if (dimension != null && field.getTypeParamsList().stream()
                .noneMatch(param -> "dim".equals(param.getKey()) && dimension.equals(param.getValue()))) {
            throw new LearningAgentServiceException("向量维度不符合预期，要求 " + dimension);
        }
    }

    // R<T> 是 SDK 的结果包装：status 表示请求结果，data 才是具体数据。
    // try/catch 只能捕获抛出的异常，无法替代对返回失败状态的检查。
    // 本方法只检查外层状态，调用方仍需按业务检查 Boolean/结构等 data 是否有效。
    private void requireSuccess(R<?> response, String message) {
        if (response == null || !Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            throw new LearningAgentServiceException(message + (response == null ? "" : "：" + response.getMessage()),
                    response == null ? null : response.getException());
        }
    }
    // 保留原来的创建方法：由 ensureCollection 在确认不存在后调用。
    // FieldType 只描述字段规则，build() 不会发请求；最后的客户端调用才真正创建集合。
    public void createCollection() {
        log.info("创建集合开始");
        List<FieldType> fields = new ArrayList<>();
        // 定义 id 字段
        FieldType idField = FieldType.newBuilder()
                // 字段名称
                .withName("id")
                // 字段描述
                .withDescription("向量记录 ID")
                // 字段数据类型
                .withDataType(DataType.Int64)
                // 是否是主键字段
                .withPrimaryKey(true)
                // 禁止 Milvus 自动生成 ID，插入时必须提供切片 ID（不是 chunkIndex）。
                .withAutoID(false)
                .build();
        // 定义 document_id 字段
        FieldType documentIdField = FieldType.newBuilder()
                .withName("document_id")
                .withDescription("文档 ID")
                .withDataType(DataType.Int64)
                .build();
        // 当前结构保留 chunk_id，它与 id 存同一个切片 ID，并非 MySQL 外键约束。
        FieldType chunkIdField = FieldType.newBuilder()
                .withName("chunk_id")
                .withDescription("MySQL document_chunks.id")
                .withDataType(DataType.Int64)
                .build();
        // 定义 embedding 字段
        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDescription("文本向量")
                .withDataType(DataType.FloatVector)
                // 每条向量必须有 1536 个 Float；需要与 Embedding API 配置一致。
                .withDimension(1536)
                .build();

        fields.add(idField);
        fields.add(documentIdField);
        fields.add(chunkIdField);
        fields.add(embeddingField);

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                // 集合名称
                .withCollectionName(COLLECTION_NAME)
                // 集合描述
                .withDescription("文档切片向量集合")
                // 字段类型列表
                .withFieldTypes(fields)
                // 强一致性控制读取对已完成写入的可见性，不会代替建索引或加载集合。
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        try {
            R<?> response = milvusClient.createCollection(param);
            requireSuccess(response, "创建 Milvus 集合失败");
        } catch (Exception e) {
            log.error("创建集合失败，具体原因：{}", e.getMessage());
            throw new LearningAgentServiceException("创建集合失败", e);
        }
        log.info("集合：{}创建成功", COLLECTION_NAME);
    }
    /**
     * 保留原来的创建方法：由 ensureIndex 在缺少索引时调用。
     * 这里声明算法和参数，不是在 Java 里训练中心；无需等有数据才声明配置。
     * Milvus 对符合条件的数据段构建索引，新数据不会因已有配置而停止构建。
     * 未建索引的增长段/小段可以走直接比较；不是每条数据都必须先等 IVF 训练完成。
     */
    public void createIndex() {
        log.info("创建索引开始");
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    // 集合名称
                    .withCollectionName(MilvusCollectionInitializer.COLLECTION_NAME)
                    // 添加索引的字段名称
                    .withFieldName("embedding")
                    // 先把向量分成若干组；搜索时选择部分组，在这些组内逐个比较完整向量。
                    .withIndexType(IndexType.IVF_FLAT)
                    // 通过向量方向的接近程度衡量相似性
                    .withMetricType(MetricType.COSINE)
                    // IVF 构建时的目标聚类数量，不是查询返回数量，也不是 128 个服务器。
                    // 查询时的 nprobe 才决定选多少组；nlist 是否合适需要结合段内数据量评估。
                    .withExtraParam("{\"nlist\":128}")
                    .build();
        try {
            R<?> response = milvusClient.createIndex(indexParam);
            requireSuccess(response, "创建 Milvus 索引失败");
        } catch (Exception e) {
            log.error("创建索引失败，具体原因：{}", e.getMessage());
            throw new LearningAgentServiceException("创建索引失败", e);
        }
        // 这里只说明本次创建请求成功；不承诺所有当前/未来数据段的索引都已构建完成。
        log.info("Milvus 索引创建请求成功");
    }
}
