package com.yjjoker.learningagent.service.impl;

import com.yjjoker.learningagent.entity.DocumentTask;
import com.yjjoker.learningagent.entity.Documents;
import com.yjjoker.learningagent.repository.DocumentRepository;
import com.yjjoker.learningagent.repository.DocumentTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 在同一个数据库事务中保存文档记录和对应的待处理任务。
@Service
@RequiredArgsConstructor
public class DocumentTaskPersistenceService {
    private final DocumentRepository documentRepository;
    private final DocumentTaskRepository documentTaskRepository;

    // 原子保存文档和任务，避免只保存文档却没有后台任务。
    @Transactional
    public void saveDocumentAndTask(Documents document, DocumentTask task) {
        int documentRows = documentRepository.save(document);
        if (documentRows != 1) {
            throw new IllegalStateException("保存文档记录失败");
        }
        task.setDocumentId(document.getId());
        int taskRows = documentTaskRepository.save(task);
        if (taskRows != 1) {
            throw new IllegalStateException("保存文档处理任务失败");
        }
    }
}
