package com.example.aiapp.service;

import com.example.aiapp.domain.dto.CreateConversationDTO;
import com.example.aiapp.domain.vo.ConversationVO;
import com.example.aiapp.domain.vo.PageVO;

import java.util.List;

public interface ConversationService {

    ConversationVO create(Long userId, CreateConversationDTO request);

    List<ConversationVO> list(Long userId);

    PageVO<ConversationVO> page(Long userId, long page, long size);

    void delete(Long userId, Long conversationId);
}
