package com.example.aiapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话附件数据访问接口。
 */
@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachmentEntity> {

    /** 查询指定消息已绑定的附件，并按位置排序。 */
    List<ChatAttachmentEntity> selectAttachedByMessageId(@Param("messageId") Long messageId);

    /** 查询当前用户重试消息时可复用的已绑定附件。 */
    List<ChatAttachmentEntity> selectAttachedForRetry(
            @Param("userId") Long userId,
            @Param("conversationId") Long conversationId,
            @Param("messageId") Long messageId
    );

    /** 最近一次包含附件的用户消息；重试时不能读取原消息之后上传的文件。 */
    List<ChatAttachmentEntity> selectRecentAttachedForConversation(
            @Param("userId") Long userId,
            @Param("conversationId") Long conversationId,
            @Param("atOrBeforeMessageId") Long atOrBeforeMessageId
    );

    /** 查询指定时间前过期的未绑定附件。 */
    List<ChatAttachmentEntity> selectExpiredReady(
            @Param("expiredBefore") LocalDateTime expiredBefore,
            @Param("limit") int limit
    );

    /** 查询当前用户拥有的指定附件集合。 */
    List<ChatAttachmentEntity> selectByUserIdAndIds(
            @Param("userId") Long userId,
            @Param("ids") List<String> ids
    );

    /** 按附件主键和用户主键查询归属记录。 */
    ChatAttachmentEntity selectByIdAndUserId(
            @Param("id") String id,
            @Param("userId") Long userId
    );
}
