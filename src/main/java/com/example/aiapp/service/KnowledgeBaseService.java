package com.example.aiapp.service;

import com.example.aiapp.domain.dto.CreateKnowledgeBaseDTO;
import com.example.aiapp.domain.vo.KnowledgeBaseVO;
import com.example.aiapp.domain.vo.PageVO;

import java.util.List;

/**
 * 用户知识库服务。
 */
public interface KnowledgeBaseService {

    KnowledgeBaseVO create(Long userId, CreateKnowledgeBaseDTO request);

    List<KnowledgeBaseVO> list(Long userId);

    PageVO<KnowledgeBaseVO> page(Long userId, long page, long size);

    void delete(Long userId, Long knowledgeBaseId);
}
