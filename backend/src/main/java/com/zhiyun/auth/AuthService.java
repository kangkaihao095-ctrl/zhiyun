package com.zhiyun.auth;

import com.zhiyun.common.ApiException;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.AppUser;
import com.zhiyun.domain.QuotaAccount;
import com.zhiyun.domain.ResearchProject;
import com.zhiyun.domain.Tenant;
import com.zhiyun.notify.InboxService;
import com.zhiyun.repo.ProjectRepo;
import com.zhiyun.repo.QuotaAccountRepo;
import com.zhiyun.repo.TenantRepo;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.JwtService;
import com.zhiyun.security.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {
    private static final String EMAIL_RE = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    private final TenantRepo tenantRepo;
    private final UserRepo userRepo;
    private final QuotaAccountRepo quotaAccountRepo;
    private final ProjectRepo projectRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AvatarService avatarService;
    private final InboxService inboxService;
    private final ZhiyunProperties properties;

    public AuthService(TenantRepo tenantRepo, UserRepo userRepo, QuotaAccountRepo quotaAccountRepo,
                       ProjectRepo projectRepo, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AvatarService avatarService, InboxService inboxService, ZhiyunProperties properties) {
        this.tenantRepo = tenantRepo;
        this.userRepo = userRepo;
        this.quotaAccountRepo = quotaAccountRepo;
        this.projectRepo = projectRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.avatarService = avatarService;
        this.inboxService = inboxService;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> register(String email, String password, String displayName, String tenantName) {
        userRepo.findByEmail(email).ifPresent(u -> {
            throw ApiException.conflict("这个邮箱已经注册过了");
        });
        Tenant tenant = new Tenant();
        tenant.setName(tenantName == null || tenantName.isBlank() ? (displayName + "的实验室") : tenantName);
        tenant = tenantRepo.save(tenant);

        AppUser user = new AppUser();
        user.setTenantId(tenant.getId());
        user.setEmail(email.trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user = userRepo.save(user);

        QuotaAccount quota = new QuotaAccount();
        quota.setTenantId(tenant.getId());
        quota.setUserId(user.getId());
        quota.setBalance(3);
        quotaAccountRepo.save(quota);

        ResearchProject project = new ResearchProject();
        project.setTenantId(tenant.getId());
        project.setName("我的论文");
        projectRepo.save(project);

        return tokenPayload(user);
    }

    public Map<String, Object> login(String email, String password) {
        AppUser user = userRepo.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> ApiException.unauthorized("invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized("邮箱或密码不对");
        }
        return tokenPayload(user);
    }

    public Map<String, Object> me() {
        AuthUser auth = TenantContext.require();
        int balance = quotaAccountRepo.findByTenantIdAndUserId(auth.tenantId(), auth.userId())
                .map(QuotaAccount::getBalance).orElse(0);
        AppUser user = userRepo.findById(auth.userId()).orElse(null);
        String tenantName = tenantRepo.findById(auth.tenantId()).map(Tenant::getName).orElse("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", auth.userId());
        out.put("tenantId", auth.tenantId());
        out.put("tenantName", tenantName);
        out.put("email", user != null ? user.getEmail() : auth.email());
        out.put("displayName", user != null && user.getDisplayName() != null ? user.getDisplayName() : auth.displayName());
        out.put("quota", balance);
        out.put("createdAt", user == null ? null : user.getCreatedAt());
        out.put("avatarUrl", avatarService.publicUrl(user));
        out.put("operator", properties.getOps().isOperator(user != null ? user.getEmail() : auth.email()));
        out.put("unreadInbox", inboxService.unreadCount());
        return out;
    }

    @Transactional
    public Map<String, Object> updateMe(String displayName, String email) {
        AuthUser auth = TenantContext.require();
        AppUser user = userRepo.findById(auth.userId()).orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (displayName != null) {
            String name = displayName.trim();
            if (name.isEmpty() || name.length() > 128) {
                throw ApiException.bad("显示名请控制在 1 到 128 个字");
            }
            user.setDisplayName(name);
        }
        if (email != null) {
            String next = email.trim().toLowerCase();
            if (!next.matches(EMAIL_RE)) {
                throw ApiException.bad("邮箱格式不对");
            }
            if (userRepo.existsByEmailAndIdNot(next, user.getId())) {
                throw ApiException.conflict("这个邮箱已经注册过了");
            }
            user.setEmail(next);
        }
        userRepo.save(user);
        return me();
    }

    private Map<String, Object> tokenPayload(AppUser user) {
        AuthUser auth = new AuthUser(user.getId(), user.getTenantId(), user.getEmail(), user.getDisplayName());
        return Map.of(
                "token", jwtService.issue(auth),
                "userId", user.getId(),
                "tenantId", user.getTenantId(),
                "email", user.getEmail(),
                "displayName", user.getDisplayName()
        );
    }
}
