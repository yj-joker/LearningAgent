package com.yjjoker.learningagent.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "milvus")
@Data
public class MilvusProperties {
    @NotBlank
    private String host;
    @NotBlank
    private int port;
}
