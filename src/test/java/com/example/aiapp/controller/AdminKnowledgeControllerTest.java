package com.example.aiapp.controller;

import com.example.aiapp.domain.entity.KnowledgeBaseEntity;
import com.example.aiapp.domain.entity.KnowledgeFileEntity;
import com.example.aiapp.domain.vo.FileUploadVO;
import com.example.aiapp.mapper.KnowledgeBaseMapper;
import com.example.aiapp.mapper.KnowledgeFileMapper;
import com.example.aiapp.service.*;
import com.example.aiapp.security.CurrentUser;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AdminKnowledgeControllerTest {
    final KnowledgeBaseMapper bases=mock(KnowledgeBaseMapper.class);
    final KnowledgeFileMapper files=mock(KnowledgeFileMapper.class);
    final FileService service=mock(FileService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final AdminKnowledgeController controller=new AdminKnowledgeController(bases,files,service,mock(KnowledgeBaseService.class),audit);
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void setup(String status){
        var base=new KnowledgeBaseEntity();base.setId(10L);base.setUserId(7L);when(bases.selectById(10L)).thenReturn(base);
        var file=new KnowledgeFileEntity();file.setId(3L);file.setKnowledgeBaseId(10L);file.setParseStatus(status);
        when(files.selectByIdAndKnowledgeBaseId(3L,10L)).thenReturn(file);
        when(files.lockAdminRetry(3L,10L)).thenReturn(3L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CurrentUser(99L,"admin","ADMIN"),null));
    }
    @Test void retryUsesOwnersScopeButAuditsActualAdministrator(){
        setup("FAILED");
        when(service.rebuild(7L,10L,3L)).thenReturn(new FileUploadVO(3L,10L,"doc.pdf","private-path",null,null,null,null,null,1L,null,"QUEUED","task-1",null));
        assertThat(controller.retry(10L,3L).ingestTaskId()).isEqualTo("task-1");
        verify(service).rebuild(7L,10L,3L);
        verify(audit).record(99L,"ADMIN_KNOWLEDGE_RETRY","KNOWLEDGE","3");
    }
    @Test void fileFromAnotherBaseIsRejectedBeforeAnyProcessing(){
        setup("FAILED");when(files.selectByIdAndKnowledgeBaseId(3L,10L)).thenReturn(null);
        assertThatThrownBy(()->controller.retry(10L,3L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service,audit);
    }
    @Test void activeTaskCannotBeRetried(){
        setup("PROCESSING");
        assertThatThrownBy(()->controller.retry(10L,3L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verifyNoInteractions(service,audit);
    }
    @Test void fileMetadataIsScopedToBaseAndNormalizesReadyStates(){
        setup("ready");
        var result = controller.file(10L,3L);
        assertThat(result.id()).isEqualTo("3");
        assertThat(result.knowledgeBaseId()).isEqualTo("10");
        assertThat(result.parseStatus()).isEqualTo("COMPLETED");
        when(files.selectByIdAndKnowledgeBaseId(3L,10L)).thenReturn(null);
        assertThatThrownBy(()->controller.file(10L,3L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(service,audit);
    }
}
