package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AiChatAttachmentDTO;
import com.example.aiapp.domain.entity.ChatAttachmentEntity;
import com.example.aiapp.domain.vo.ChatAttachmentVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 对话附件服务。
 */
public interface ChatAttachmentService {

    ChatAttachmentVO upload(Long userId, MultipartFile file, String requestedKind);

    ChatAttachmentVO upload(
            Long userId,
            MultipartFile file,
            String requestedKind,
            String sourceType,
            String folderGroupId,
            String folderRoot,
            String relativePath
    );

    List<ChatAttachmentVO> listForMessage(Long messageId);

    List<AiChatAttachmentDTO> requireReady(Long userId, List<String> rawIds);

    List<AiChatAttachmentDTO> requireRecentForConversation(Long userId, String conversationId);

    List<AiChatAttachmentDTO> requireAttachedForRetry(
            Long userId,
            String conversationId,
            Long messageId,
            List<String> rawIds
    );

    void bind(Long userId, String conversationId, Long messageId, List<String> rawIds);

    void removeDraft(Long userId, String attachmentId);

    AttachmentContent content(Long userId, String attachmentId);

    record AttachmentContent(ChatAttachmentEntity entity, InputStream stream) {
    }
}
