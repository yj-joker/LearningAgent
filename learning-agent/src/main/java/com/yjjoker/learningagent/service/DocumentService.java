package com.yjjoker.learningagent.service;

import com.yjjoker.learningagent.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
   DocumentVO uploadDocument(MultipartFile file, Long kbId);

   /**
    * 校验下载权限并准备文件流及响应元数据。
    */
   com.yjjoker.learningagent.vo.DocumentDownload prepareDownload(Long documentId);
}
