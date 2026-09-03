package com.example.aiapp.service;

import com.example.aiapp.domain.dto.ChatRequestDTO;
import com.example.aiapp.domain.vo.ChatMessageVO;
import com.example.aiapp.domain.vo.ChatResponseVO;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 对话编排服务。
 */
public interface ChatService {

    ChatResponseVO send(Long userId, ChatRequestDTO request);

    void stream(Long userId, ChatRequestDTO request, Consumer<String> chunkConsumer);

    ChatMessageVO cancelTurn(Long userId, String conversationId, String turnId);

    List<ChatMessageVO> listMessages(Long userId, String conversationId);
}
