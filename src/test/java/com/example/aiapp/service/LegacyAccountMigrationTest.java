package com.example.aiapp.service;

import com.example.aiapp.config.WechatLoginProperties;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegacyAccountMigrationTest {
    UserMapper users=mock(UserMapper.class);
    PasswordEncoder passwords=mock(PasswordEncoder.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    EmailIdentityService emails=mock(EmailIdentityService.class);
    AuthService auth=mock(AuthService.class);
    LegacyAccountMigration service;
    UserEntity user=new UserEntity();
    String ticket="a".repeat(43);
    @BeforeEach void setup() {
        user.setId(7L); user.setAuthVersion(1); user.setStatus("ACTIVE"); user.setPasswordHash("bcrypt");
        when(users.selectByUsername("old")).thenReturn(user);
        when(users.selectAccountById(7L)).thenReturn(user);
        when(passwords.matches("password","bcrypt")).thenReturn(true);
        when(redis.opsForValue()).thenReturn(values);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(7L),eq(7L))).thenReturn(0L);
        service=new LegacyAccountMigration(users,passwords,redis,jdbc,emails,auth,
            new WechatLoginProperties(true,"wx1234567890123456","a".repeat(32),"https://luckyzll.site/api/auth/wechat/callback"));
    }
    @Test void passwordProofNeverCreatesAccountOrSession() {
        assertEquals(43,service.begin("old","password",null).length());
        verifyNoInteractions(auth,emails);
        verify(values).set(anyString(),eq("7:1"),eq(java.time.Duration.ofMinutes(10)));
    }
    @Test void wrongPasswordOrDisabledAccountCannotMigrate() {
        assertThrows(ResponseStatusException.class,()->service.begin("old","wrong",null));
        user.setStatus("DISABLED");
        assertThrows(ResponseStatusException.class,()->service.begin("old","password",null));
        verifyNoInteractions(redis,auth,emails);
    }
    @Test void boundAccountCannotUseOldPasswordPath() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(7L),eq(7L))).thenReturn(1L);
        assertThrows(ResponseStatusException.class,()->service.begin("old","password",null));
        verifyNoInteractions(auth,emails,redis);
    }
    @Test void proofExpiresIsSingleUseAndTracksAuthVersion() {
        when(values.getAndDelete("lyle:legacy-migration:"+ticket)).thenReturn("7:1",null);
        assertEquals(7L,service.require(ticket,true));
        assertThrows(ResponseStatusException.class,()->service.require(ticket,true));
        when(values.get("lyle:legacy-migration:"+ticket)).thenReturn("7:1");
        user.setAuthVersion(2);
        assertThrows(ResponseStatusException.class,()->service.require(ticket,false));
    }
    @Test void emailBindingPreservesOriginalIdAndNeverRegisters() {
        service.bindEmail(7L,"old@example.com",null,"local");
        verify(emails).bind(7L,"old@example.com");
        verify(auth).loginVerifiedUser(7L,null,"local");
        verifyNoMoreInteractions(emails,auth);
    }
    @Test void wechatBindingPreservesOriginalId() {
        service.bindWechat(7L,"open",null,"local");
        verify(jdbc).update(anyString(),eq("wx1234567890123456"),eq("open"),eq(7L));
        verify(auth).loginVerifiedUser(7L,null,"local");
        verifyNoInteractions(emails);
    }
}
