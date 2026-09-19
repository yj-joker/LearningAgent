package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

//阿里云 Embedding 接口配置。
@ConfigurationProperties(prefix = "aliyun.embedding")
@Validated
@Data
public class AliyunEmbeddingProperties {

    //阿里云兼容 OpenAI 协议的 Embedding 接口地址
    @NotBlank
    private String baseUrl;
    //从环境变量 DASHSCOPE_API_KEY 注入的 API Key
    @NotBlank
    private String apiKey;
    @NotBlank
    //使用的模型名称
    @NotBlank
    private String model;
    //向量维度，必须与向量数据库 Collection 的维度一致
    @NotNull
    private int dimensions ;
    //单次请求最多发送的文本数量，qwen3.7 系列当前建议不超过 20
    @NotNull
    private int maxBatchSize;
    // 单个应用实例同时调用阿里云 Embedding 接口的最大请求数。
    @Min(1)
    private int maxConcurrency = 2;
    // 等待阿里云并发许可的最长时间，超时后让任务进入现有失败或重试流程。
    @Min(1)
    private int acquireTimeoutSeconds = 30;
}
