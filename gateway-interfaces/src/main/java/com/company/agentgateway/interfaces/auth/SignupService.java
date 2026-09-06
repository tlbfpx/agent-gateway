package com.company.agentgateway.interfaces.auth;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.application.admin.auth.PasswordHasher;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminStatus;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 自助注册服务（spec 2026-09-04 §self-serve-signup §3）。
 *
 * <p>公开端点 {@code POST /v1/auth/signup} 后端调用本 service：
 * <ol>
 *   <li>校验输入：email 合法、password ≥ 8、companyName 1-64</li>
 *   <li>从 companyName 派生 tenantId（lowercase + 非字母数字替换 + 截断 + 8 hex 随机后缀避免冲突）</li>
 *   <li>bcrypt 哈希密码，建 AdminUser（OWNER 角色；demo signup 首个管理员当 OWNER）</li>
 *   <li>直接调 AdminAuthService.login 拿 session token，无需额外注册步骤</li>
 *   <li>返回 {@link SignupResult}：tenantId / email / session token</li>
 * </ol>
 *
 * <p>失败语义：
 * <ul>
 *   <li>参数非法 → IllegalArgumentException（controller 转 400）</li>
 *   <li>email 重复 → EmailAlreadyExistsException（controller 转 409）</li>
 * </ul>
 */
@Service
public class SignupService {

    /** 累计 signup 次数（spec §business-stats round 39）。重启清零。 */
    private final java.util.concurrent.atomic.AtomicLong signupCount =
            new java.util.concurrent.atomic.AtomicLong(0);

    public long getSignupCount() {
        return signupCount.get();
    }

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern EMAIL_RE =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final AdminUserRepository adminUserRepo;
    private final AdminAuthService adminAuthService;

    public SignupService(AdminUserRepository adminUserRepo,
                         AdminAuthService adminAuthService) {
        this.adminUserRepo = adminUserRepo;
        this.adminAuthService = adminAuthService;
    }

    public SignupResult signup(String email, String password, String companyName) {
        validate(email, password, companyName);
        String tenantId = deriveTenantId(companyName);

        // 1) bcrypt + 持久化 AdminUser（OWNER）
        String phc = PasswordHasher.hash(password);
        AdminUser user = new AdminUser(
                0L,
                email.toLowerCase(Locale.ROOT),
                companyName,
                AdminRole.OWNER,
                AdminStatus.ACTIVE,
                tenantId,
                phc,
                java.time.Instant.now(),
                null);

        if (adminUserRepo.findByEmail(tenantId, user.email()).isPresent()) {
            throw new EmailAlreadyExistsException(user.email());
        }
        adminUserRepo.save(user);

        // 2) login → 拿 session token
        AdminAuthService.LoginResult login;
        try {
            login = adminAuthService.login(tenantId, user.email(), password);
        } catch (Exception e) {
            log.warn("signup.login.failed email={} tenant={} msg={}",
                    user.email(), tenantId, e.getMessage());
            throw new IllegalStateException("login after signup failed", e);
        }

        log.info("signup.ok tenant={} email={}", tenantId, user.email());
        signupCount.incrementAndGet();
        return new SignupResult(tenantId, user.email(), login.token());
    }

    private void validate(String email, String password, String companyName) {
        if (email == null || !EMAIL_RE.matcher(email).matches()) {
            throw new IllegalArgumentException("invalid email: " + email);
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
        if (companyName == null || companyName.isBlank()
                || companyName.length() > 64) {
            throw new IllegalArgumentException(
                    "companyName must be 1-64 characters: " + companyName);
        }
    }

    /** 从 companyName 派生 URL-safe tenantId；冲突时仅作为 fallback（email 检查兜底）。 */
    static String deriveTenantId(String companyName) {
        String base = companyName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) base = "tenant";
        if (base.length() > 24) base = base.substring(0, 24);
        byte[] rnd = new byte[4];
        RNG.nextBytes(rnd);
        return base + "-" + java.util.HexFormat.of().formatHex(rnd);
    }

    /** 409: email already exists（service-level 区分，让 controller 不用关心字符串匹配）。 */
    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String email) {
            super("email already exists: " + email);
        }
    }
}