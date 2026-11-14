package com.example.aiapp.service;
import com.example.aiapp.mapper.*;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.billing.BillingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AdminUserServiceTest {
    final UserMapper users=mock(UserMapper.class);
    final CommercialAccountMapper accounts=mock(CommercialAccountMapper.class);
    final BillingAccountService billing=mock(BillingAccountService.class);
    final BillingProperties properties=mock(BillingProperties.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AdminUserService service=new AdminUserService(users,accounts,billing,properties,audit,jdbc);
    final AdminUserService.GrantRequest request=new AdminUserService.GrantRequest("operation-test-0001",1000000L,30,"业务测试");
    void user(){var u=new UserEntity();u.setId(7L);u.setStatus("ACTIVE");when(users.selectAccountById(7L)).thenReturn(u);when(accounts.selectAccountByUserId(7L,false)).thenReturn(null);}
    Map<String,Object> row(String status){return Map.of("actor_id",99L,"user_id",7L,"operation_type","GRANT","credit_micros",1000000L,"validity_days",30,"reason","业务测试","status",status);}
    @Test void viewingUninitializedCreditsOrLedgerNeverCreatesAccountOrWelcomeGrant(){
        user();assertThat(service.credits(7).initialized()).isFalse();assertThat(service.ledger(7,"",100)).isEmpty();
        verifyNoInteractions(billing,jdbc,audit);verify(accounts,never()).insertAccountIgnore(anyLong(),anyLong(),any());
    }
    @Test void repeatedSameRequestDoesNotGrantOrAuditAgain(){
        user();when(properties.ledgerEnabled()).thenReturn(true);when(properties.userViewEnabled()).thenReturn(true);
        when(jdbc.queryForMap(anyString(),eq(request.requestId()))).thenReturn(row("PENDING"),row("APPLIED"));
        service.grant(99,7,request);service.grant(99,7,request);
        verify(billing,times(1)).grantAdminCredits(eq(7L),argThat(r->r.creditMicros()==1000000&&r.reason().equals("业务测试")));
        verify(audit,times(1)).record(99L,"ADMIN_CREDIT_GRANT","USER","7");
    }
    @Test void reusingRequestForDifferentQuantityIsRejectedBeforeGrant(){
        user();when(properties.ledgerEnabled()).thenReturn(true);when(properties.userViewEnabled()).thenReturn(true);
        when(jdbc.queryForMap(anyString(),eq(request.requestId()))).thenReturn(row("APPLIED"));
        assertThatThrownBy(()->service.grant(99,7,new AdminUserService.GrantRequest(request.requestId(),2000000L,30,"业务测试")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verifyNoInteractions(billing,audit);
    }
    @Test void disabledUserAndUnknownUserCannotReceiveCredits(){
        user();users.selectAccountById(7L).setStatus("DISABLED");
        assertThatThrownBy(()->service.grant(99,7,request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.grant(99,8,request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(billing,jdbc,audit);
    }
    @Test void actualAdminGrantDoesNotRunWelcomeCreditInitialization(){
        when(accounts.selectAccountByUserId(7L,false)).thenReturn(Map.of("id",11L));
        var actual=new com.example.aiapp.service.impl.BillingAccountServiceImpl(accounts,new com.fasterxml.jackson.databind.ObjectMapper(),properties,mock(com.example.aiapp.billing.BillingMetrics.class));
        actual.grantAdminCredits(7L,new com.example.aiapp.billing.BillingRequests.GrantCredits("admin-request-1234",1000000L,30,"测试原因"));
        verifyNoInteractions(properties);
        verify(accounts,times(1)).insertGrant(anyLong(),eq(11L),eq("MANUAL"),eq("OPERATOR"),eq("admin-request-1234"),eq("AI_CREDIT"),isNull(),eq(1000000L),any(),any(),contains("测试原因"),any());
    }
    @Test void ledgerConvertsJdbcTimestampAndPreservesPreciseCreditsAndReason(){
        user();when(accounts.selectAccountByUserId(7L,false)).thenReturn(Map.of("id",11L));
        var row=new HashMap<String,Object>();row.put("id",999999999999999999L);row.put("entry_type","MANUAL_ADJUSTMENT");row.put("direction","CREDIT");row.put("credit_micros",9000000000000000L);row.put("balance_after_micros",9000000000000000L);row.put("source_id","request");row.put("occurred_at",java.sql.Timestamp.valueOf("2026-11-05 08:00:00"));row.put("metadata_json","{\"reason\":\"业务补偿\"}");
        when(accounts.selectLedger(11L,null,100)).thenReturn(List.of(row));
        var result=service.ledger(7,"",100).get(0);
        assertThat(result.creditMicros()).isEqualTo("9000000000000000");assertThat(result.id()).isEqualTo("999999999999999999");assertThat(result.occurredAt()).isEqualTo(java.time.LocalDateTime.of(2026,11,5,8,0));assertThat(result.reason()).isEqualTo("业务补偿");
        verifyNoInteractions(billing,jdbc,audit);
    }
}
