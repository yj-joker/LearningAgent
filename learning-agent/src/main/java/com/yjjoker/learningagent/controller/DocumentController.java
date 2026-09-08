package com.yjjoker.learningagent.controller;

import com.yjjoker.learningagent.entity.Result;
import com.yjjoker.learningagent.service.DocumentService;
import com.yjjoker.learningagent.vo.DocumentDownload;
import com.yjjoker.learningagent.vo.DocumentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/document")
@Tag(name = "文档管理")
@AllArgsConstructor
public class DocumentController {
    private final DocumentService documentService;
    // 上传文档
    @PostMapping("/upload/{kbId}")
    @Operation(summary = "上传文档")
     public Result<DocumentVO> upload(@RequestParam("file") MultipartFile file, @PathVariable @NotNull @Positive Long kbId) {
        return Result.success(documentService.uploadDocument(file, kbId));
    }
    // 下载文档
    @GetMapping("/download/{documentId}")
    @Operation(summary = "下载文档")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable @NotNull @Positive Long documentId
    ) {
        DocumentDownload documentDownload = documentService.prepareDownload(documentId);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (documentDownload.getMimeType() != null && !documentDownload.getMimeType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(documentDownload.getMimeType());
            } catch (IllegalArgumentException ignored) {
                // 非法 MIME 类型时使用通用二进制类型，避免阻塞下载。
            }
        }
        String filename = documentDownload.getFilename();
        if (filename == null || filename.isBlank()) {
            filename = "download-" + documentId;
        }

        // 不包装 Result，否则文件流会被当成 JSON 响应处理。
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(documentDownload.getResponseBody());
    }
    // 删除文档
}
