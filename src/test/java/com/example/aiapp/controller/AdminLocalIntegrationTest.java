package com.example.aiapp.controller;

import com.example.aiapp.security.AuthSessionService;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.service.AuditLogService;
import com.example.aiapp.mapper.CommercialAccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.mybatis.spring.SqlSessionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

/** Opt-in tests for a new, dedicated local MySQL instance; never use application credentials. */
@EnabledIfEnvironmentVariable(named = "LYLE_ADMIN_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/admin-local-empty.properties", "spring.profiles.active=admin-local-integration", "server.address=127.0.0.1",
        "spring.flyway.enabled=true", "spring.flyway.baseline-on-migrate=false",
        "app.scheduling.enabled=false", "app.redis.enabled=false", "app.tos.enabled=false",
        "app.minio.enabled=false", "app.qdrant.enabled=false", "app.mission.enabled=false",
        "app.tos.access-key-id=", "app.tos.secret-access-key=",
        "app.auth.jwt-secret=isolated-admin-integration-secret-never-used-outside-tests",
        "app.auth.allowed-origins=http://127.0.0.1:5176,http://localhost:5176",
        "app.auth.secure-cookie=false", "app.auth.session-v64-enabled=true",
        "ai.service.base-url=http://127.0.0.1:18401", "ai.service.internal-token=isolated-integration-internal-token",
        "app.billing.ledger-enabled=true", "app.billing.user-view-enabled=true",
        "app.billing.free-credit-micros=10000000", "app.billing.payment-provider=MOCK",
        "app.billing.fulfillment-worker-enabled=false", "app.billing.reconciliation-enabled=false",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=13979", "spring.mail.host=127.0.0.1"
})
@ContextConfiguration(initializers = AdminLocalIntegrationTest.IsolatedDefaults.class)
class AdminLocalIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(900_000_000_000_000_000L + System.currentTimeMillis());
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AuthSessionService sessions;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwords;
    @SpyBean AuditLogService audit;
    @SpyBean CommercialAccountMapper accounts;
    @Autowired SqlSessionTemplate sqlSession;
    long actor;
    long target;
    String token;

    public static class IsolatedDefaults implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yml"));
            var defaults = Objects.requireNonNull(yaml.getObject());
            // Reuse current application defaults without processing its optional .env imports.
            defaults.remove("spring.config.import");
            context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("isolated-application-defaults", defaults));
        }
    }

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url = System.getenv("LYLE_ADMIN_IT_JDBC_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13316/lyle_admin_it(?:\\?.*)?")) {
            throw new IllegalStateException("Only the dedicated local integration database is allowed.");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("LYLE_ADMIN_IT_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("LYLE_ADMIN_IT_PASSWORD"));
    }

    @BeforeEach void fixtures() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("lyle_admin_it");
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13316);
        actor = addUser("ADMIN", "ACTIVE");
        target = addUser("USER", "ACTIVE");
        token = access(actor, "ADMIN");
    }

    @Test void migrationAndReadOnlyQueriesDoNotCreateAccountsOrWelcomeGrants() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='34' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='35' AND success=1",Integer.class)).isEqualTo(1);
        var creditList=body(request("GET","/admin/credits?keyword="+target,token,null));
        assertThat(creditList.path("records").get(0).path("credit").path("initialized").asBoolean()).isFalse();
        var result = request("GET", "/admin/users/" + target + "/credits", token, null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(body(result).path("initialized").asBoolean()).isFalse();
        assertThat(body(result).path("availableCreditMicros").asText()).isEqualTo("0");
        assertThat(body(request("GET", "/admin/users/" + target + "/ledger", token, null)).isEmpty()).isTrue();
        assertThat(accountCount()).isZero();
        var users = body(request("GET", "/admin/users?keyword=" + target + "&status=ACTIVE", token, null));
        assertThat(users.path("records").get(0).path("id").asText()).isEqualTo(String.valueOf(target));
        assertThat(users.path("records").get(0).has("passwordHash")).isFalse();
    }

    @Test void verifiedEmailsAppearInListAndDetailAndCanBeSearchedWithoutChangingAccounts() throws Exception {
        String email="admin-contact-"+target+"@"+"e".repeat(63)+".example.com";
        jdbc.update("INSERT INTO auth_email_identity (email,user_id,verified_at) VALUES (?,?,NOW())",email,target);
        long disabled=addUser("USER","DISABLED");
        jdbc.update("INSERT INTO auth_email_identity (email,user_id,verified_at) VALUES (?,?,NOW())","disabled-"+email,disabled);
        var response=request("GET","/admin/users?keyword="+email.toUpperCase(java.util.Locale.ROOT)+"&status=ACTIVE",token,null);
        assertThat(response.statusCode()).isEqualTo(200);
        var matches=body(response);
        assertThat(matches.path("total").asInt()).isEqualTo(1);
        assertThat(matches.path("records").get(0).path("id").asText()).isEqualTo(String.valueOf(target));
        assertThat(matches.path("records").get(0).path("email").asText()).isEqualTo(email);
        var detail=body(request("GET","/admin/users/"+target,token,null));
        assertThat(detail.path("email").asText()).isEqualTo(email);
        assertThat(detail.has("passwordHash")).isFalse();
        assertThat(body(request("GET","/admin/users/"+actor,token,null)).path("email").isNull()).isTrue();
        jdbc.update("UPDATE app_user SET username=? WHERE id=?","邮箱查询验证-"+target,target);
        String unicode=java.net.URLEncoder.encode("邮箱查询验证-"+target,java.nio.charset.StandardCharsets.UTF_8);
        var byName=request("GET","/admin/users?keyword="+unicode,token,null);
        assertThat(byName.statusCode()).isEqualTo(200);
        assertThat(body(byName).path("records").get(0).path("email").asText()).isEqualTo(email);
        String hostile=java.net.URLEncoder.encode("' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body(request("GET","/admin/users?keyword="+hostile,token,null)).path("total").asInt()).isZero();
        assertThat(accountCount()).isZero();
    }

    @Test void concurrentRetriesCommitExactlyOneGrantLedgerEntryAndAudit() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var payload = grant(requestId, 1_000_001L);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) tasks.add(() -> request("POST", "/admin/users/" + target + "/credits/grants", token, payload));
            for (var future : pool.invokeAll(tasks)) {
                var result = future.get();
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(body(result).path("availableCreditMicros").asText()).isEqualTo("1000001");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credit_operation WHERE request_id=? AND status='APPLIED'", Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commercial_ledger_entry WHERE source_id=?", Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_CREDIT_GRANT' AND target_id=?", Integer.class, String.valueOf(target))).isEqualTo(1);
        var credit = body(request("GET", "/admin/users/" + target + "/credits", token, null));
        assertThat(credit.path("availableCreditMicros").asText()).isEqualTo("1000001");
        assertThat(credit.path("totalGrantedCreditMicros").asText()).isEqualTo("1000001");
        var entries = body(request("GET", "/admin/users/" + target + "/ledger", token, null));
        assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.get(0).path("reason").asText()).isEqualTo("integration correction");
    }

    @Test void distinctConcurrentGrantsProduceContinuousLedgerBalances() throws Exception {
        assertThat(request("POST", "/admin/users/" + target + "/credits/grants", token, grant(UUID.randomUUID().toString(), 1)).statusCode()).isEqualTo(200);
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                var payload = grant(UUID.randomUUID().toString(), 1);
                tasks.add(() -> request("POST", "/admin/users/" + target + "/credits/grants", token, payload));
            }
            for (var response : pool.invokeAll(tasks)) assertThat(response.get().statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        var entries = body(request("GET", "/admin/users/" + target + "/ledger", token, null));
        List<Long> balances = new ArrayList<>();
        entries.forEach(entry -> balances.add(Long.parseLong(entry.path("balanceAfterMicros").asText())));
        assertThat(balances).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(body(request("GET", "/admin/users/" + target + "/credits", token, null)).path("availableCreditMicros").asText()).isEqualTo("7");
    }

    @Test void distinctConcurrentFirstGrantsCreateOneAccountWithContinuousLedgerBalances() throws Exception {
        int participants = 6;
        var beforeInsert = new CyclicBarrier(participants);
        // Hold each insertion until every request has observed a missing account.
        // Execute the actual mapped SQL on its existing transaction connection.
        doAnswer(invocation -> {
            beforeInsert.await(15, TimeUnit.SECONDS);
            long accountId = invocation.getArgument(0);
            long userId = invocation.getArgument(1);
            LocalDateTime createdAt = invocation.getArgument(2);
            return sqlSession.insert(CommercialAccountMapper.class.getName() + ".insertAccountIgnore", Map.of(
                    "id", accountId, "userId", userId, "createdAt", createdAt));
        }).when(accounts).insertAccountIgnore(anyLong(), eq(target), any());
        var pool = Executors.newFixedThreadPool(participants);
        try {
            List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < participants; i++) {
                var payload = grant(UUID.randomUUID().toString(), 1);
                tasks.add(() -> request("POST", "/admin/users/" + target + "/credits/grants", token, payload));
            }
            for (var response : pool.invokeAll(tasks)) assertThat(response.get().statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accountCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credit_operation WHERE user_id=? AND status='APPLIED'", Integer.class, target)).isEqualTo(participants);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_CREDIT_GRANT' AND target_id=?", Integer.class, String.valueOf(target))).isEqualTo(participants);
        var entries = body(request("GET", "/admin/users/" + target + "/ledger", token, null));
        List<Long> balances = new ArrayList<>();
        entries.forEach(entry -> balances.add(Long.parseLong(entry.path("balanceAfterMicros").asText())));
        assertThat(balances).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
        var credit = body(request("GET", "/admin/users/" + target + "/credits", token, null));
        assertThat(credit.path("availableCreditMicros").asText()).isEqualTo("6");
        assertThat(credit.path("totalGrantedCreditMicros").asText()).isEqualTo("6");
    }

    @Test void changedRequestAndDisabledAccountsCannotReceiveExtraCredits() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(request("POST", "/admin/users/" + target + "/credits/grants", token, grant(id, 10)).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/admin/users/" + target + "/credits/grants", token, grant(id, 20)).statusCode()).isEqualTo(409);
        long anotherActor = addUser("ADMIN", "ACTIVE");
        assertThat(request("POST", "/admin/users/" + target + "/credits/grants", access(anotherActor, "ADMIN"), grant(id, 10)).statusCode()).isEqualTo(409);
        long disabled = addUser("USER", "DISABLED");
        assertThat(request("POST", "/admin/users/" + disabled + "/credits/grants", token, grant(UUID.randomUUID().toString(), 10)).statusCode()).isEqualTo(409);
        assertThat(body(request("GET", "/admin/users/" + target + "/credits", token, null)).path("availableCreditMicros").asText()).isEqualTo("10");
    }

    @Test void auditFailureRollsBackTheAccountGrantLedgerAndIdempotencyRecord() throws Exception {
        String id = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("integration fault injection")).when(audit)
                .record(actor, "ADMIN_CREDIT_GRANT", "USER", String.valueOf(target));
        assertThat(request("POST", "/admin/users/" + target + "/credits/grants", token, grant(id, 100)).statusCode()).isEqualTo(500);
        assertThat(accountCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credit_operation WHERE request_id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commercial_ledger_entry WHERE source_id=?", Integer.class, id)).isZero();
    }

    @Test void knowledgeQueriesAndRenameUseTheCorrectOwnerAndHideStorageMetadata() throws Exception {
        long base = IDS.incrementAndGet(), other = IDS.incrementAndGet(), file = IDS.incrementAndGet();
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name,created_at) VALUES(?,?,?,NOW())", base, target, "integration knowledge");
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name,created_at) VALUES(?,?,?,NOW())", other, actor, "other knowledge");
        jdbc.update("INSERT INTO knowledge_file(id,knowledge_base_id,file_name,storage_path,parse_status,created_at) VALUES(?,?,?,?,?,NOW())", file, base, "test.pdf", "integration-only-private-path", "ready");
        var list = body(request("GET", "/admin/knowledge-bases?userId=" + target, token, null));
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("records").get(0).path("id").asText()).isEqualTo(String.valueOf(base));
        var files = body(request("GET", "/admin/knowledge-bases/" + base + "/files?status=COMPLETED", token, null));
        assertThat(files.path("total").asInt()).isEqualTo(1);
        assertThat(files.path("records").get(0).path("parseStatus").asText()).isEqualTo("COMPLETED");
        var detail = body(request("GET", "/admin/knowledge-bases/" + base + "/files/" + file, token, null));
        assertThat(detail.has("storagePath")).isFalse();
        assertThat(request("GET", "/admin/knowledge-bases/" + other + "/files/" + file, token, null).statusCode()).isEqualTo(404);
        assertThat(request("PATCH", "/admin/knowledge-bases/" + base, token, Map.of("name", "renamed knowledge")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT name FROM knowledge_base WHERE id=?", String.class, base)).isEqualTo("renamed knowledge");
        assertThat(jdbc.queryForObject("SELECT user_id FROM audit_log WHERE action='ADMIN_KNOWLEDGE_RENAME' AND target_id=?", Long.class, String.valueOf(base))).isEqualTo(actor);
    }

    @Test void actualSessionsEnforcePermissionsAndRotateRefreshCookies() throws Exception {
        assertThat(request("GET", "/admin/users", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/admin/users", access(target, "USER"), null).statusCode()).isEqualTo(403);
        var grant = sessions.create(actor, 1, "integration-" + actor, "ADMIN", "ACTIVE", null, "integration-test", "127.0.0.1");
        var refreshRequest = HttpRequest.newBuilder(uri("/auth/refresh"))
                .header("Origin", "http://127.0.0.1:5176")
                .header("Cookie", "lyle_refresh=" + grant.refreshToken())
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = HTTP.send(refreshRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "Path=/api/auth");
        String refreshedToken = body(response).path("accessToken").asText();
        assertThat(request("GET", "/admin/users", refreshedToken, null).statusCode()).isEqualTo(200);
        sessions.revokeCurrent(grant.sessionId(), actor, "INTEGRATION_TEST");
        assertThat(request("GET", "/admin/users", refreshedToken, null).statusCode()).isEqualTo(401);
    }

    @Test void overviewConfigurationAndAuditUseTheRealDatabase() throws Exception {
        assertThat(request("GET", "/admin/overview", token, null).statusCode()).isEqualTo(200);
        var configs = body(request("GET", "/admin/ai/config", token, null));
        assertThat(configs.isArray()).isTrue();
        assertThat(configs.isEmpty()).isFalse();
        var config = configs.get(0);
        assertThat(request("PUT", "/admin/ai/config", token, Map.of("key", config.path("key").asText(), "value", config.path("value").asText())).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/admin/audit-logs?page=1&size=5", token, null).statusCode()).isEqualTo(200);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LYLE_ADMIN_IT_UI", matches = "true")
    void actualVueBrowserUsesTheJavaServerAndCommitsToTheIsolatedDatabase() throws Exception {
        jdbc.update("INSERT INTO auth_email_identity (email,user_id,verified_at) VALUES (?,?,NOW())","integration-"+target+"@example.com",target);
        long base = IDS.incrementAndGet(), file = IDS.incrementAndGet();
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name,created_at) VALUES(?,?,?,NOW())", base, target, "integration UI knowledge");
        jdbc.update("INSERT INTO knowledge_file(id,knowledge_base_id,file_name,storage_path,parse_status,created_at) VALUES(?,?,?,?,?,NOW())", file, base, "integration-ui.pdf", "integration-only-private-path", "failed");
        var session = sessions.create(actor, 1, "integration-" + actor, "ADMIN", "ACTIVE", null, "integration-browser", "127.0.0.1");
        Path frontend = Path.of(System.getProperty("user.dir")).getParent().resolve("lyle-admin-vue");
        assertThat(Files.isRegularFile(frontend.resolve("scripts/verify-local-integration.mjs"))).isTrue();
        Path log = frontend.resolve("test-results/integration/browser.log");
        Files.createDirectories(log.getParent());
        var builder = new ProcessBuilder("node.exe", "scripts/verify-local-integration.mjs")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("LYLE_ADMIN_IT_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("LYLE_ADMIN_IT_REFRESH", session.refreshToken());
        builder.environment().put("LYLE_ADMIN_IT_USER_ID", String.valueOf(target));
        var process = builder.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Browser integration timed out; inspect " + log);
        }
        assertThat(process.exitValue()).withFailMessage("Browser integration failed: %s", Files.readString(log)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(g.remaining_micros) FROM entitlement_grant g JOIN commercial_account a ON a.id=g.account_id WHERE a.user_id=?", Long.class, target)).isEqualTo(1_000_000L);
        assertThat(jdbc.queryForObject("SELECT name FROM knowledge_base WHERE id=?", String.class, base)).isEqualTo("local UI renamed knowledge");
    }

    private Map<String,Object> deduction(String requestId,long amount) {
        return Map.of("requestId",requestId,"creditMicros",amount,"reason","integration correction");
    }
    private String creditsPath() { return "/admin/users/"+target+"/credits"; }
    @Test void deductionsAreIdempotentAndSeparateFromUsageAndGrantRequests() throws Exception {
        String grantId=UUID.randomUUID().toString();
        assertThat(request("POST",creditsPath()+"/grants",token,grant(grantId,1_000_001)).statusCode()).isEqualTo(200);
        var payload=deduction(UUID.randomUUID().toString(),250_001);
        var pool=Executors.newFixedThreadPool(6);
        try {
            List<Callable<HttpResponse<String>>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++)tasks.add(()->request("POST",creditsPath()+"/deductions",token,payload));
            for(var future:pool.invokeAll(tasks))assertThat(future.get().statusCode()).isEqualTo(200);
        } finally { pool.shutdownNow(); }
        var credit=body(request("GET",creditsPath(),token,null));
        assertThat(credit.path("availableCreditMicros").asText()).isEqualTo("750000");
        assertThat(credit.path("totalGrantedCreditMicros").asText()).isEqualTo("1000001");
        assertThat(credit.path("totalDeductedCreditMicros").asText()).isEqualTo("250001");
        assertThat(credit.path("totalConsumedCreditMicros").asText()).isEqualTo("0");
        var entries=body(request("GET","/admin/users/"+target+"/ledger?type=ADMIN_DEDUCTION",token,null));
        assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.get(0).path("reason").asText()).isEqualTo("integration correction");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_CREDIT_DEDUCT' AND target_id=?",Integer.class,String.valueOf(target))).isEqualTo(1);
        assertThat(request("POST",creditsPath()+"/deductions",token,deduction(grantId,1_000_001)).statusCode()).isEqualTo(409);
        assertThat(request("POST",creditsPath()+"/grants",token,grant((String)payload.get("requestId"),250_001)).statusCode()).isEqualTo(409);
        var list=body(request("GET","/admin/credits?keyword="+target,token,null));
        assertThat(list.path("records").get(0).path("credit")).isEqualTo(credit);
        assertThat(request("GET","/admin/credits",access(target,"USER"),null).statusCode()).isEqualTo(403);
        assertThat(request("POST",creditsPath()+"/deductions",access(target,"USER"),deduction(UUID.randomUUID().toString(),1)).statusCode()).isEqualTo(403);
    }
    @Test void deductionsProtectReservationsAndRejectUninitializedDisabledAndInsufficientAccounts() throws Exception {
        assertThat(request("POST",creditsPath()+"/deductions",token,deduction(UUID.randomUUID().toString(),1)).statusCode()).isEqualTo(409);
        assertThat(accountCount()).isZero();
        assertThat(request("POST",creditsPath()+"/grants",token,grant(UUID.randomUUID().toString(),100)).statusCode()).isEqualTo(200);
        long account=jdbc.queryForObject("SELECT id FROM commercial_account WHERE user_id=?",Long.class,target);
        jdbc.update("INSERT INTO commercial_reservation(id,account_id,user_id,turn_id,reserved_credit_micros,consumed_credit_micros,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,80,0,'ACTIVE',DATE_ADD(NOW(),INTERVAL 1 DAY),NOW(),NOW())",IDS.incrementAndGet(),account,target,UUID.randomUUID().toString());
        var rejected=deduction(UUID.randomUUID().toString(),21);
        assertThat(request("POST",creditsPath()+"/deductions",token,rejected).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credit_operation WHERE request_id=?",Integer.class,rejected.get("requestId"))).isZero();
        assertThat(body(request("GET",creditsPath(),token,null)).path("deductibleCreditMicros").asText()).isEqualTo("20");
        assertThat(request("POST",creditsPath()+"/deductions",token,deduction(UUID.randomUUID().toString(),20)).statusCode()).isEqualTo(200);
        assertThat(body(request("GET",creditsPath(),token,null)).path("availableCreditMicros").asText()).isEqualTo("80");
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE id=?",target);
        assertThat(request("POST",creditsPath()+"/deductions",token,deduction(UUID.randomUUID().toString(),1)).statusCode()).isEqualTo(409);
    }
    @Test void simultaneousDeductionsCannotOverdrawAndAuditFailureRollsBack() throws Exception {
        assertThat(request("POST",creditsPath()+"/grants",token,grant(UUID.randomUUID().toString(),100)).statusCode()).isEqualTo(200);
        doThrow(new IllegalStateException("integration rollback")).when(audit).record(eq(actor),eq("ADMIN_CREDIT_DEDUCT"),eq("USER"),eq(String.valueOf(target)));
        String failed=UUID.randomUUID().toString();
        assertThat(request("POST",creditsPath()+"/deductions",token,deduction(failed,1)).statusCode()).isEqualTo(500);
        assertThat(body(request("GET",creditsPath(),token,null)).path("availableCreditMicros").asText()).isEqualTo("100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credit_operation WHERE request_id=?",Integer.class,failed)).isZero();
        org.mockito.Mockito.reset(audit);
        var pool=Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks=List.of(
                ()->request("POST",creditsPath()+"/deductions",token,deduction(UUID.randomUUID().toString(),80)).statusCode(),
                ()->request("POST",creditsPath()+"/deductions",token,deduction(UUID.randomUUID().toString(),80)).statusCode());
            var results=pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
        assertThat(body(request("GET",creditsPath(),token,null)).path("availableCreditMicros").asText()).isEqualTo("20");
    }

    @Test void deductionsSpendEarliestExpiryAndExcludeExpiredAndFutureGrants() throws Exception {
        List<String> ids=new ArrayList<>();
        for(long amount:List.of(10L,20L,30L,40L)) {
            String id=UUID.randomUUID().toString();ids.add(id);
            assertThat(request("POST",creditsPath()+"/grants",token,grant(id,amount)).statusCode()).isEqualTo(200);
        }
        jdbc.update("UPDATE entitlement_grant SET expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE source_id=?",ids.get(0));
        jdbc.update("UPDATE entitlement_grant SET valid_from=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE source_id=?",ids.get(3));
        jdbc.update("UPDATE entitlement_grant SET expires_at=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE source_id=?",ids.get(2));
        assertThat(body(request("GET",creditsPath(),token,null)).path("availableCreditMicros").asText()).isEqualTo("50");
        assertThat(request("POST",creditsPath()+"/deductions",token,deduction(UUID.randomUUID().toString(),35)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT remaining_micros FROM entitlement_grant WHERE source_id=?",Long.class,ids.get(2))).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_micros FROM entitlement_grant WHERE source_id=?",Long.class,ids.get(1))).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT remaining_micros FROM entitlement_grant WHERE source_id=?",Long.class,ids.get(0))).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT remaining_micros FROM entitlement_grant WHERE source_id=?",Long.class,ids.get(3))).isEqualTo(40);
        assertThat(body(request("GET",creditsPath(),token,null)).path("totalDeductedCreditMicros").asText()).isEqualTo("35");
    }

    private int accountCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commercial_account WHERE user_id=?", Integer.class, target);
    }

    private long addUser(String role, String status) {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,status,auth_version,created_at) VALUES(?,?,?,?,?,1,NOW())",
                id, "integration-" + id, passwords.encode(UUID.randomUUID().toString()), role, status);
        return id;
    }

    private String access(long user, String role) {
        var session = sessions.create(user, 1, "integration-" + user, role, "ACTIVE", LocalDateTime.now(), "integration-test", "127.0.0.1");
        return jwt.generateToken(user, session.username(), role, session.sessionId(), 1);
    }

    private Map<String, Object> grant(String id, long quantity) {
        return Map.of("requestId", id, "creditMicros", quantity, "validityDays", 30, "reason", "integration correction");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    private HttpResponse<String> request(String method, String path, String accessToken, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (payload != null) request.header("Content-Type", "application/json");
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }
}
