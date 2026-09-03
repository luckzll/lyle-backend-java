package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 对话消息数据访问接口。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /** 按创建时间升序查询会话消息。 */
    List<ChatMessageEntity> selectByConversationId(String conversationId);

    /** 查询指定轮次最近一条助手消息。 */
    ChatMessageEntity selectByConversationIdAndTurnId(String conversationId, String turnId);

    /** 查询指定轮次最近一条用户消息。 */
    ChatMessageEntity selectUserByConversationIdAndTurnId(String conversationId, String turnId);

    /** 删除指定会话的全部消息。 */
    int deleteByConversationId(String conversationId);
}
