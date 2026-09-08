package com.yjjoker.learningagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 下载文档时返回给 Controller 的响应元数据和文件流。
 */
@Data
@AllArgsConstructor
public class DocumentDownload {
    private String filename;
    private String mimeType;
    private StreamingResponseBody responseBody;
}
