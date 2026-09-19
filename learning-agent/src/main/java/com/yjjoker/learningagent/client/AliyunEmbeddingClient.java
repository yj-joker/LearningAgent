package com.yjjoker.learningagent.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yjjoker.learningagent.config.AliyunEmbeddingProperties;
import com.yjjoker.learningagent.entity.EmbeddingResult;
import com.yjjoker.learningagent.exception.LearningAgentServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 阿里云 Embedding 客户端。
// 这个类只负责和阿里云文本向量接口通信：接收文本、发送 HTTP 请求、校验响应并返回向量。
@Component
@Slf4j
public class AliyunEmbeddingClient {

    private final AliyunEmbeddingProperties properties;
    private final AliyunEmbeddingConcurrencyLimiter concurrencyLimiter;
    private final RestClient restClient;

    // 创建只供阿里云 Embedding 使用的 HTTP 客户端，并分别限制连接和读取等待时间。
    public AliyunEmbeddingClient(
            AliyunEmbeddingProperties properties,
            AliyunEmbeddingConcurrencyLimiter concurrencyLimiter) {
        this.properties = properties;
        this.concurrencyLimiter = concurrencyLimiter;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 连接超时限制建立 TCP 和 TLS 连接的等待时间，避免网络不可达时长期占用解析线程。
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        // 读取超时限制连接成功后等待响应数据的时间，避免请求长期占用并发许可。
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();

        log.info("阿里云 Embedding HTTP 客户端初始化完成，connectTimeoutSeconds={}，readTimeoutSeconds={}",
                properties.getConnectTimeoutSeconds(), properties.getReadTimeoutSeconds());
    }

    // 批量生成文本向量。
    public List<EmbeddingResult> embedDocuments(List<String> texts) {
        //校验texts格式是否正确
        validateInput(texts);

        // 构造请求体。
        EmbeddingRequest request = new EmbeddingRequest(
                properties.getModel(), //模型名称
                texts,//待向量化的文本列表
                properties.getDimensions(),//向量长度
                "float"//表示希望接口返回浮点数向量
        );

        // 发送 POST 请求。
        EmbeddingResponse response;
        try {
            // 每次真正发送 HTTP 请求前获取独立的阿里云并发许可，不降低其他文档处理阶段的并发度。
            response = concurrencyLimiter.execute(() -> sendEmbeddingRequest(request));
        } catch (RestClientException exception) {
            // 网络错误、连接超时以及 HTTP 错误会进入这里。
            log.error("调用阿里云 Embedding 接口失败，文本数量：{}", texts.size(), exception);
            throw new LearningAgentServiceException("调用文本向量服务失败，请稍后重试", exception);
        }

        //校验阿里云返回的数据，并依据 index 恢复成输入文本的顺序。
        return validateAndOrderResponse(response, texts.size());
    }

    // 向阿里云发送一次 Embedding HTTP 请求，这个方法只能通过并发限制器调用。
    private EmbeddingResponse sendEmbeddingRequest(EmbeddingRequest request) {
        return restClient
                .post()
                .uri(properties.getBaseUrl())
                // 设置请求头，指定内容类型为 JSON。
                .contentType(MediaType.APPLICATION_JSON)
                // 设置响应内容类型为 JSON。
                .accept(MediaType.APPLICATION_JSON)
                // 设置请求头，包含 API Key。
                .header("Authorization", "Bearer " + requireApiKey())
                // 设置请求体，包含要向量化的文本列表。
                .body(request)
                // 发送请求并获取响应。
                .retrieve()
                // 获取响应体并反序列化为 EmbeddingResponse 对象。
                .body(EmbeddingResponse.class);
    }

    // 校验请求参数。
    private void validateInput(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new LearningAgentServiceException("待向量化的文本不能为空");
        }
        if (texts.size() > properties.getMaxBatchSize()) {
            throw new LearningAgentServiceException(
                    "单次向量化文本数量不能超过 " + properties.getMaxBatchSize());
        }
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new LearningAgentServiceException("待向量化的文本不能包含空文本");
        }
    }

    // 读取并校验 API Key。
    // 把这个检查放到真正发请求前，可以在配置错误时立即失败，而不是得到难以定位的鉴权错误。
    private String requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new LearningAgentServiceException("未配置阿里云 Embedding API Key");
        }
        return properties.getApiKey();
    }

    // 检验响应结果，避免错误数据流入 Milvus,造成后续数据和切片错位。
    private List<EmbeddingResult> validateAndOrderResponse(
            EmbeddingResponse response, int expectedCount) {
        if (response == null || response.data() == null) {
            throw new LearningAgentServiceException("阿里云 Embedding 返回结果为空");
        }
        if (response.code() != null && !response.code().isBlank()) {
            String message = response.message() == null ? "未知错误" : response.message();
            throw new LearningAgentServiceException("阿里云 Embedding 调用失败：" + message);
        }
        if (response.data().size() != expectedCount) {
            throw new LearningAgentServiceException("阿里云 Embedding 返回数量与输入数量不一致");
        }

        // 使用 index 建立“输入下标 -> 向量”的映射，而不是盲目依赖返回数组顺序。
        // 同时可以检测重复下标、越界下标以及缺失下标。
        Map<Integer, EmbeddingData> indexedData = new HashMap<>();
        for (EmbeddingData data : response.data()) {
            if (data == null){
                log.error("阿里云 Embedding 返回了无效的文本向量：data未null");
                throw new LearningAgentServiceException("阿里云 Embedding 返回了无效的文本向量");
            }
            //判断下标是否越界，或者重复
            if (data.index() < 0 || data.index() >= expectedCount
                    || indexedData.put(data.index(), data) != null) {
                    log.error("阿里云 Embedding 返回了无效的文本下标：{}", data.index());
                throw new LearningAgentServiceException("阿里云 Embedding 返回了无效的文本下标");
            }
            //判断向量是否为空，或者维度是否正确
            if (data.embedding() == null || data.embedding().size() != properties.getDimensions()) {
                log.error("阿里云 Embedding 返回的向量维度不正确");
                throw new LearningAgentServiceException("阿里云 Embedding 返回向量维度不正确");
            }
        }
        //判断返回的向量数量是否与输入数量一致
        if (indexedData.size() != expectedCount) {
            log.error("阿里云 Embedding 返回结果缺少文本向量");
            throw new LearningAgentServiceException("阿里云 Embedding 返回结果缺少文本向量");
        }

        // 最后按原始文本下标排序，保证返回列表与 texts 的顺序一致。
        // 避免可能出现的下标错误
        return indexedData.values().stream()
                .sorted(Comparator.comparingInt(EmbeddingData::index))
                .map(data -> new EmbeddingResult(data.index(), data.embedding()))
                .toList();
    }

    // 定义请求体结构。
    private record EmbeddingRequest(
            String model,
            List<String> input,
            Integer dimensions,
            @JsonProperty("encoding_format") String encodingFormat) {
    }

    // 定义响应结构。
    private record EmbeddingResponse(
            List<EmbeddingData> data,
            String model,
            String code,
            String message,
            EmbeddingUsage usage) {
    }
    // 定义向量数据结构。
    // index：输入文本的下标。也就是输入文本在 texts 列表中的索引位置。
    private record EmbeddingData(int index, List<Double> embedding) {
    }

    // 显示此次调用的使用情况。
    private record EmbeddingUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
