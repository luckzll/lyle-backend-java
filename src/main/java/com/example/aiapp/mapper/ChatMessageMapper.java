package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.ChatMessageEntity;

import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    List<ChatMessageEntity> selectByConversationId(String conversationId);

    ChatMessageEntity selectByConversationIdAndTurnId(String conversationId, String turnId);

    ChatMessageEntity selectUserByConversationIdAndTurnId(String conversationId, String turnId);

    int deleteByConversationId(String conversationId);
}
