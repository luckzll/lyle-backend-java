package com.example.aiapp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.aiapp.billing.BillingProperties;
import com.example.aiapp.billing.BillingRequests;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.mapper.CommercialAccountMapper;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class AdminUserService {
    private final UserMapper users;
    private final CommercialAccountMapper accounts;
    private final BillingAccountService billing;
    private final BillingProperties properties;
    private final AuditLogService audit;
    private final JdbcTemplate jdbc;
    public AdminUserService(UserMapper users,CommercialAccountMapper accounts,BillingAccountService billing,
            BillingProperties properties,AuditLogService audit,JdbcTemplate jdbc){
        this.users=users;this.accounts=accounts;this.billing=billing;this.properties=properties;this.audit=audit;this.jdbc=jdbc;
    }
    public record UserView(String id,String username,String role,String status,LocalDateTime createdAt,LocalDateTime lastLoginAt){}
    public record CreditView(boolean initialized,String status,String availableCreditMicros,String totalGrantedCreditMicros,String totalConsumedCreditMicros,LocalDateTime nextExpiryAt){}
    public record LedgerView(String id,String entryType,String direction,String creditMicros,String balanceAfterMicros,String sourceId,LocalDateTime occurredAt,LocalDateTime expiresAt,String reason){}
    public record GrantRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId,
            @Min(1) @Max(9000000000000000L) long creditMicros,
            @Min(1) @Max(3650) Integer validityDays,@NotBlank @Size(max=512) String reason){}
    public PageVO<UserView> list(long page,long size,String keyword,String status){
        var query=new QueryWrapper<UserEntity>().select("id","username","role","status","created_at","last_login_at");
        if(!keyword.isBlank())query.and(w->{w.like("username",keyword.trim());if(keyword.matches("[0-9]{1,19}"))w.or().eq("id",keyword);});
        if(!status.isBlank())query.eq("status",status);
        query.orderByDesc("created_at").orderByDesc("id");
        var result=users.selectPage(Page.of(page,size),query);
        return new PageVO<>(result.getRecords().stream().map(this::view).toList(),result.getTotal(),page,size);
    }
    public UserView detail(long id){return view(requireUser(id));}
    public CreditView credits(long userId){
        requireUser(userId);
        var account=accounts.selectAccountByUserId(userId,false);
        if(account==null)return new CreditView(false,"NOT_INITIALIZED","0","0","0",null);
        long id=number(account.get("id"));
        return new CreditView(true,String.valueOf(account.get("status")),str(accounts.sumAvailableCredits(id)),
                str(accounts.sumGrantedCredits(id)),str(accounts.sumConsumedCredits(id)),accounts.selectNextExpiryAt(id));
    }
    public List<LedgerView> ledger(long userId,String type,int limit){
        requireUser(userId);
        var account=accounts.selectAccountByUserId(userId,false);
        if(account==null)return List.of();
        return accounts.selectLedger(number(account.get("id")),type.isBlank()?null:type,limit).stream()
                .map(row->new LedgerView(str(row.get("id")),String.valueOf(row.get("entry_type")),String.valueOf(row.get("direction")),
                        str(row.get("credit_micros")),str(row.get("balance_after_micros")),String.valueOf(row.get("source_id")),
                        time(row.get("occurred_at")),time(row.get("expires_at")),reason(row.get("metadata_json")))).toList();
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreditView grant(long actor,long userId,GrantRequest request){
        var user=requireUser(userId);
        if(!"ACTIVE".equalsIgnoreCase(user.getStatus()))throw new ResponseStatusException(CONFLICT,"账号不可用，不能发放额度。");
        if(!properties.ledgerEnabled()||!properties.userViewEnabled())throw new ResponseStatusException(CONFLICT,"商业额度功能尚未启用。");
        // Acquire an exclusive key lock directly; INSERT IGNORE can deadlock when
        // simultaneous duplicates upgrade their shared locks at SELECT FOR UPDATE.
        jdbc.update("INSERT INTO admin_credit_operation (request_id,actor_id,user_id,credit_micros,validity_days,reason,status,created_at) VALUES (?,?,?,?,?,?,'PENDING',?) ON DUPLICATE KEY UPDATE request_id=admin_credit_operation.request_id",
                request.requestId(),actor,userId,request.creditMicros(),request.validityDays(),request.reason().trim(),LocalDateTime.now());
        var row=jdbc.queryForMap("SELECT * FROM admin_credit_operation WHERE request_id=? FOR UPDATE",request.requestId());
        if(number(row.get("actor_id"))!=actor||number(row.get("user_id"))!=userId||number(row.get("credit_micros"))!=request.creditMicros()
                ||!Objects.equals(row.get("validity_days"),request.validityDays())||!request.reason().trim().equals(row.get("reason")))
            throw new ResponseStatusException(CONFLICT,"请求编号已用于其他操作，请勿修改重试内容。");
        if(!"APPLIED".equals(row.get("status"))){
            billing.grantAdminCredits(userId,new BillingRequests.GrantCredits(request.requestId(),request.creditMicros(),request.validityDays(),request.reason().trim()));
            audit.record(actor,"ADMIN_CREDIT_GRANT","USER",String.valueOf(userId));
            jdbc.update("UPDATE admin_credit_operation SET status='APPLIED' WHERE request_id=?",request.requestId());
        }
        return credits(userId);
    }
    private UserEntity requireUser(long id){var user=users.selectAccountById(id);if(user==null)throw new ResponseStatusException(NOT_FOUND,"用户不存在");return user;}
    private UserView view(UserEntity u){return new UserView(String.valueOf(u.getId()),u.getUsername(),u.getRole(),u.getStatus(),u.getCreatedAt(),u.getLastLoginAt());}
    private static long number(Object v){return v==null?0:((Number)v).longValue();}
    private static String str(Object v){return v==null?"0":String.valueOf(v);}
    private static LocalDateTime time(Object v){if(v==null)return null;if(v instanceof LocalDateTime t)return t;if(v instanceof java.sql.Timestamp t)return t.toLocalDateTime();return LocalDateTime.parse(String.valueOf(v).replace(' ','T'));}
    private static String reason(Object value){
        if(value==null)return "";
        if(value instanceof Map<?,?> map)return String.valueOf(map.getOrDefault("reason",null)==null?"":map.get("reason"));
        try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(value)).path("reason").asText("");}
        catch(Exception ignored){return "";}
    }
}
