package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.CreateConversationDTO;
import com.example.aiapp.domain.dto.UpdateConversationDTO;
import com.example.aiapp.domain.vo.ConversationVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.service.ConversationService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    ConversationVO create(@Valid @RequestBody CreateConversationDTO request) {
        return conversationService.create(CurrentUserUtils.currentUserId(), request);
    }

    @GetMapping
    List<ConversationVO> list() {
        return conversationService.list(CurrentUserUtils.currentUserId());
    }

    @GetMapping("/page")
    PageVO<ConversationVO> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return conversationService.page(CurrentUserUtils.currentUserId(), page, size);
    }

    @PatchMapping("/{conversationId}")
    ConversationVO updateTitle(
            @PathVariable Long conversationId,
            @Valid @RequestBody UpdateConversationDTO request
    ) {
        return conversationService.updateTitle(CurrentUserUtils.currentUserId(), conversationId, request);
    }

    @DeleteMapping("/{conversationId}")
    void delete(@PathVariable Long conversationId) {
        conversationService.delete(CurrentUserUtils.currentUserId(), conversationId);
    }
}
