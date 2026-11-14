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
    public record UserView(String id,String username,String email,String role,String status,LocalDateTime createdAt,LocalDateTime lastLoginAt){}
    public record CreditView(boolean initialized,String status,String availableCreditMicros,String totalGrantedCreditMicros,String totalConsumedCreditMicros,LocalDateTime nextExpiryAt,String reservedCreditMicros,String deductibleCreditMicros,String totalDeductedCreditMicros){}
    public record CreditUserView(UserView user,CreditView credit){}
    public record LedgerView(String id,String entryType,String direction,String creditMicros,String balanceAfterMicros,String sourceId,LocalDateTime occurredAt,LocalDateTime expiresAt,String reason){}
    public record GrantRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId,
            @Min(1) @Max(9000000000000000L) long creditMicros,
            @Min(1) @Max(3650) Integer validityDays,@NotBlank @Size(max=512) String reason){}
    public record DeductRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId,
            @Min(1) @Max(9000000000000000L) long creditMicros,@NotBlank @Size(max=512) String reason){}

    public PageVO<CreditUserView> creditUsers(long page,long size,String keyword,String status){
        var result=list(page,size,keyword,status);
        var records=result.records();
        if(records.isEmpty())return new PageVO<>(List.of(),result.total(),page,size);
        var ids=records.stream().map(u->Long.valueOf(u.id())).toArray();
        // One read-only query for the current page; no account initialization or welcome grants.
        String sql="""
            SELECT a.user_id,a.status,
              (SELECT COALESCE(SUM(g.quantity_micros),0) FROM entitlement_grant g WHERE g.account_id=a.id) AS granted,
              (SELECT COALESCE(SUM(g.remaining_micros),0) FROM entitlement_grant g WHERE g.account_id=a.id
                AND g.status='ACTIVE' AND g.remaining_micros>0 AND g.valid_from<=CURRENT_TIMESTAMP(6)
                AND (g.expires_at IS NULL OR g.expires_at>CURRENT_TIMESTAMP(6))) AS available,
              (SELECT MIN(g.expires_at) FROM entitlement_grant g WHERE g.account_id=a.id AND g.status='ACTIVE'
                AND g.remaining_micros>0 AND g.valid_from<=CURRENT_TIMESTAMP(6) AND g.expires_at>CURRENT_TIMESTAMP(6)) AS expiry,
              (SELECT COALESCE(SUM(e.credit_micros),0) FROM commercial_ledger_entry e
                WHERE e.account_id=a.id AND e.entry_type='USAGE_DEBIT') AS consumed,
              (SELECT COALESCE(SUM(e.credit_micros),0) FROM commercial_ledger_entry e
                WHERE e.account_id=a.id AND e.entry_type='ADMIN_DEDUCTION' AND e.direction='DEBIT') AS deducted,
              (SELECT COALESCE(SUM(r.reserved_credit_micros-r.consumed_credit_micros),0) FROM commercial_reservation r
                WHERE r.account_id=a.id AND r.status='ACTIVE' AND r.expires_at>CURRENT_TIMESTAMP(6)) AS reserved
            FROM commercial_account a WHERE a.user_id IN (
            """+String.join(",",Collections.nCopies(ids.length,"?"))+")";
        Map<Long,CreditView> views=new HashMap<>();
        for(var row:jdbc.queryForList(sql,ids)){
            long available=number(row.get("available")),reserved=number(row.get("reserved"));
            views.put(number(row.get("user_id")),new CreditView(true,String.valueOf(row.get("status")),str(row.get("available")),
                str(row.get("granted")),str(row.get("consumed")),time(row.get("expiry")),str(row.get("reserved")),
                String.valueOf(Math.max(0,available-reserved)),str(row.get("deducted"))));
        }
        return new PageVO<>(records.stream().map(u->new CreditUserView(u,views.getOrDefault(Long.valueOf(u.id()),emptyCredits()))).toList(),result.total(),page,size);
    }
    public PageVO<UserView> list(long page,long size,String keyword,String status){
        var query=new QueryWrapper<UserEntity>().select("id","username","role","status","created_at","last_login_at");
        String search=keyword.trim();
        if(!search.isBlank())query.and(w->{
            w.like("username",search);
            if(search.matches("[0-9]{1,19}"))w.or().eq("id",search);
            // Email identities use ASCII; Unicode username searches must remain valid.
            w.or().exists("SELECT 1 FROM auth_email_identity e WHERE e.user_id=app_user.id AND CONVERT(e.email USING utf8mb4) COLLATE utf8mb4_bin LIKE {0}","%"+search.toLowerCase(Locale.ROOT)+"%");
        });
        if(!status.isBlank())query.eq("status",status);
        query.orderByDesc("created_at").orderByDesc("id");
        var result=users.selectPage(Page.of(page,size),query);
        var emails=emails(result.getRecords());
        return new PageVO<>(result.getRecords().stream().map(u->view(u,emails.get(u.getId()))).toList(),result.getTotal(),page,size);
    }
    public UserView detail(long id){var user=requireUser(id);return view(user,emails(List.of(user)).get(id));}
    public CreditView credits(long userId){
        requireUser(userId);
        var account=accounts.selectAccountByUserId(userId,false);
        if(account==null)return emptyCredits();
        long id=number(account.get("id"));
        long available=number(accounts.sumAvailableCredits(id)),reserved=number(accounts.sumActiveReservationCredits(id));
        return new CreditView(true,String.valueOf(account.get("status")),String.valueOf(available),
                str(accounts.sumGrantedCredits(id)),str(accounts.sumConsumedCredits(id)),accounts.selectNextExpiryAt(id),
                String.valueOf(reserved),String.valueOf(Math.max(0,available-reserved)),str(accounts.sumAdminDeductedCredits(id)));
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
        requireAdjustmentUser(userId);
        var row=operation(actor,userId,"GRANT",request.requestId(),request.creditMicros(),request.validityDays(),request.reason());
        if(!"APPLIED".equals(row.get("status"))){
            billing.grantAdminCredits(userId,new BillingRequests.GrantCredits(request.requestId(),request.creditMicros(),request.validityDays(),request.reason().trim()));
            audit.record(actor,"ADMIN_CREDIT_GRANT","USER",String.valueOf(userId));
            jdbc.update("UPDATE admin_credit_operation SET status='APPLIED' WHERE request_id=?",request.requestId());
        }
        return credits(userId);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreditView deduct(long actor,long userId,DeductRequest request){
        requireAdjustmentUser(userId);
        var row=operation(actor,userId,"DEDUCT",request.requestId(),request.creditMicros(),null,request.reason());
        if(!"APPLIED".equals(row.get("status"))){
            billing.deductAdminCredits(userId,request.requestId(),request.creditMicros(),request.reason().trim());
            audit.record(actor,"ADMIN_CREDIT_DEDUCT","USER",String.valueOf(userId));
            jdbc.update("UPDATE admin_credit_operation SET status='APPLIED' WHERE request_id=?",request.requestId());
        }
        return credits(userId);
    }
    private void requireAdjustmentUser(long userId){
        var user=requireUser(userId);
        if(!"ACTIVE".equalsIgnoreCase(user.getStatus()))throw new ResponseStatusException(CONFLICT,"账号不可用，不能调整额度。");
        if(!properties.ledgerEnabled()||!properties.userViewEnabled())throw new ResponseStatusException(CONFLICT,"商业额度功能尚未启用。");
    }
    private Map<String,Object> operation(long actor,long userId,String operation,String requestId,long creditMicros,Integer validityDays,String reason){
        // Acquire an exclusive key lock directly; INSERT IGNORE can deadlock when
        // simultaneous duplicates upgrade their shared locks at SELECT FOR UPDATE.
        jdbc.update("INSERT INTO admin_credit_operation (request_id,actor_id,user_id,operation_type,credit_micros,validity_days,reason,status,created_at) VALUES (?,?,?,?,?,?,?,'PENDING',?) ON DUPLICATE KEY UPDATE request_id=admin_credit_operation.request_id",
                requestId,actor,userId,operation,creditMicros,validityDays,reason.trim(),LocalDateTime.now());
        var row=jdbc.queryForMap("SELECT * FROM admin_credit_operation WHERE request_id=? FOR UPDATE",requestId);
        if(number(row.get("actor_id"))!=actor||number(row.get("user_id"))!=userId||number(row.get("credit_micros"))!=creditMicros
                ||!operation.equals(row.get("operation_type"))||!Objects.equals(row.get("validity_days"),validityDays)||!reason.trim().equals(row.get("reason")))
            throw new ResponseStatusException(CONFLICT,"请求编号已用于其他操作，请勿修改重试内容。");
        return row;
    }
    private static CreditView emptyCredits(){return new CreditView(false,"NOT_INITIALIZED","0","0","0",null,"0","0","0");}
    private UserEntity requireUser(long id){var user=users.selectAccountById(id);if(user==null)throw new ResponseStatusException(NOT_FOUND,"用户不存在");return user;}
    private Map<Long,String> emails(List<UserEntity> page){
        if(page.isEmpty())return Map.of();
        var ids=page.stream().map(UserEntity::getId).toArray();
        var rows=jdbc.queryForList("SELECT user_id,email FROM auth_email_identity WHERE user_id IN ("+String.join(",",Collections.nCopies(ids.length,"?"))+")",ids);
        Map<Long,String> result=new HashMap<>();
        for(var row:rows)result.put(number(row.get("user_id")),(String)row.get("email"));
        return result;
    }
    private UserView view(UserEntity u,String email){return new UserView(String.valueOf(u.getId()),u.getUsername(),email,u.getRole(),u.getStatus(),u.getCreatedAt(),u.getLastLoginAt());}
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
