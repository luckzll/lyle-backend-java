package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.CreateKnowledgeBaseDTO;
import com.example.aiapp.domain.dto.UpdateKnowledgeBaseDTO;
import com.example.aiapp.domain.vo.KnowledgeBaseVO;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.service.KnowledgeBaseService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户知识库接口。
 */
@RestController
@RequestMapping("/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    KnowledgeBaseVO create(@Valid @RequestBody CreateKnowledgeBaseDTO request) {
        return knowledgeBaseService.create(CurrentUserUtils.currentUserId(), request);
    }

    @GetMapping
    List<KnowledgeBaseVO> list() {
        return knowledgeBaseService.list(CurrentUserUtils.currentUserId());
    }

    @GetMapping("/page")
    PageVO<KnowledgeBaseVO> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
    ) {
        return knowledgeBaseService.page(CurrentUserUtils.currentUserId(), page, size);
    }

    @PatchMapping("/{knowledgeBaseId}")
    KnowledgeBaseVO updateName(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody UpdateKnowledgeBaseDTO request
    ) {
        return knowledgeBaseService.updateName(CurrentUserUtils.currentUserId(), knowledgeBaseId, request);
    }

    @DeleteMapping("/{knowledgeBaseId}")
    void delete(@PathVariable Long knowledgeBaseId) {
        knowledgeBaseService.delete(CurrentUserUtils.currentUserId(), knowledgeBaseId);
    }
}
