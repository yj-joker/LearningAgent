package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 集中保存阿里云大语言模型聊天接口所需的可变配置。
// 调用代码只从这个对象读取配置，不直接读取环境变量，这样测试时可以手动构造配置对象，
// 将来修改环境变量名称时也不需要改动 AliyunLlmClient 的 HTTP 调用逻辑。
@ConfigurationProperties(prefix = "aliyun.llm")
@Validated
@Data
public class AliyunLlmProperties {

    // 聊天接口的完整地址，例如阿里云 OpenAI 兼容接口的 /chat/completions 地址。
    // 使用配置而不是写死在客户端里，是为了允许测试连接本地模拟服务器，也方便以后切换网关地址。
    @NotBlank
    private String baseUrl;

    // 调用阿里云接口时使用的身份凭证，通过 application.yml 从环境变量或本地 .env 注入。
    // API Key 不能直接写进源码，否则提交代码时可能一起泄露到 Git 仓库。
    @NotBlank
    private String apiKey;

    // 本次请求使用的模型名称，例如 qwen-plus。
    // 模型属于运行配置，更换模型时不应该要求重新修改和编译 Java 代码。
    @NotBlank
    private String model;

    // 建立网络连接最多等待多少秒，避免目标地址不可达时请求线程一直阻塞。
    @Min(1)
    private int connectTimeoutSeconds = 5;

    // 连接成功后最多等待模型返回多少秒；模型生成文本通常比普通数据库请求更慢。
    @Min(1)
    private int readTimeoutSeconds = 60;
}
