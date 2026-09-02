package com.example.aiapp.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("commercial_reservation")
public class CommercialReservationEntity {
    @TableId
    private String id;
    @TableField("account_id")
    private Long accountId;
    @TableField("user_id")
    private Long userId;
    @TableField("turn_id")
    private String turnId;
    @TableField("reserved_credit_micros")
    private Long reservedCreditMicros;
    @TableField("consumed_credit_micros")
    private Long consumedCreditMicros;
    private String status;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Long getReservedCreditMicros() { return reservedCreditMicros; }
    public void setReservedCreditMicros(Long reservedCreditMicros) { this.reservedCreditMicros = reservedCreditMicros; }
    public Long getConsumedCreditMicros() { return consumedCreditMicros; }
    public void setConsumedCreditMicros(Long consumedCreditMicros) { this.consumedCreditMicros = consumedCreditMicros; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
